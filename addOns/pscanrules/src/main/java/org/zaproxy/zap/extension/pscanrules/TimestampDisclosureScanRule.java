/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2014 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.zap.extension.pscanrules;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.htmlparser.jericho.Source;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.core.scanner.Plugin.AlertThreshold;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpHeaderField;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.addon.commonlib.CommonAlertTag;
import org.zaproxy.addon.commonlib.PolicyTag;
import org.zaproxy.addon.commonlib.ResourceIdentificationUtils;
import org.zaproxy.zap.extension.pscan.PluginPassiveScanner;

/**
 * A class to passively scan responses for Timestamps, since these *may* be interesting from a
 * security standpoint
 *
 * @author 70pointer@gmail.com
 */
public class TimestampDisclosureScanRule extends PluginPassiveScanner
        implements CommonPassiveScanRuleInfo {

    // We are only interested in events within a 10 year span
    private static final long EPOCH_Y2038 = 2147483647L;
    private static final ZonedDateTime ZONED_NOW = ZonedDateTime.now();

    private static final Date RANGE_START = Date.from(ZONED_NOW.minusYears(10).toInstant());
    private static final Date RANGE_STOP =
            new Date(
                    TimeUnit.SECONDS.toMillis(
                            Math.min(
                                    EPOCH_Y2038,
                                    ZONED_NOW.plusYears(10).toInstant().getEpochSecond())));
    private static final Instant ONE_YEAR_AGO = ZONED_NOW.minusYears(1).toInstant();
    private static final Instant ONE_YEAR_FROM_NOW = ZONED_NOW.plusYears(1).toInstant();

    /** a map of a regular expression pattern to details of the timestamp type found */
    static Map<Pattern, String> timestampPatterns = new HashMap<>();

    static {
        // 8 digits match CSS RGBA colors and with a very high false positive rate.
        // They also only match up to March 3, 1973 which is not worth considering.
        //
        // 9 digits match up to September 9, 2001 which is also really below any
        // interesting scope (it's more than 20 years ago).
        // As such, it's only worth looking at 10 digits.
        //
        // 2,000,000,000 is May 18, 2033 which is really beyond any interesting scope
        // at this time. At the time of this comment, it was more than 10 years in the
        // future. But it isn't a lot past 10 years, so we'll select 10 years as the
        // range.
        //
        // As such, we'll consider 2 billion series, but stop at:
        // 2147483647 which is posix time clock rollover.
        timestampPatterns.put(Pattern.compile("\\b(?:1\\d|2[0-2])\\d{8}\\b(?!%)"), "Unix");
    }

    private static final Logger LOGGER = LogManager.getLogger(TimestampDisclosureScanRule.class);

    /** Prefix for internationalized messages used by this rule */
    private static final String MESSAGE_PREFIX = "pscanrules.timestampdisclosure.";

    private static final Map<String, String> ALERT_TAGS;

    static {
        Map<String, String> alertTags =
                new HashMap<>(
                        CommonAlertTag.toMap(
                                CommonAlertTag.OWASP_2021_A01_BROKEN_AC,
                                CommonAlertTag.OWASP_2017_A03_DATA_EXPOSED));
        alertTags.put(PolicyTag.PENTEST.getTag(), "");
        ALERT_TAGS = Collections.unmodifiableMap(alertTags);
    }

    /**
     * ignore the following response headers for the purposes of the comparison, since they cause
     * false positives
     */
    public static final List<String> RESPONSE_HEADERS_TO_IGNORE =
            List.of(
                    HttpHeader._KEEP_ALIVE,
                    HttpHeader.CACHE_CONTROL,
                    "ETag",
                    "Age",
                    "Strict-Transport-Security",
                    "Report-To",
                    "NEL",
                    "Expect-CT",
                    "RateLimit-Reset",
                    "X-RateLimit-Reset",
                    "X-Rate-Limit-Reset");

    @Override
    public String getName() {
        return Constant.messages.getString(MESSAGE_PREFIX + "name");
    }

    @Override
    public void scanHttpResponseReceive(HttpMessage msg, int id, Source source) {
        if (ResourceIdentificationUtils.isFont(msg)
                || (this.getAlertThreshold().equals(AlertThreshold.HIGH)
                        && ResourceIdentificationUtils.isJavaScript(msg))) {
            return;
        }

        LOGGER.debug("Checking message {} for timestamps", msg.getRequestHeader().getURI());

        List<HttpHeaderField> responseparts = new ArrayList<>();
        msg.getResponseHeader().getHeaders().stream()
                .filter(header -> !containsIgnoreCase(RESPONSE_HEADERS_TO_IGNORE, header.getName()))
                .forEach(responseparts::add);
        // Empty 'name' for body
        responseparts.add(new HttpHeaderField("", msg.getResponseBody().toString()));

        // try each of the patterns in turn against the response.
        String timestampType = null;
        Iterator<Pattern> patternIterator = timestampPatterns.keySet().iterator();
        AlertThreshold threshold = this.getAlertThreshold();

        while (patternIterator.hasNext()) {
            Pattern timestampPattern = patternIterator.next();
            timestampType = timestampPatterns.get(timestampPattern);
            LOGGER.debug(
                    "Trying Timestamp Pattern: {} for timestamp type {}",
                    timestampPattern,
                    timestampType);
            for (HttpHeaderField haystack : responseparts) {
                Matcher matcher = timestampPattern.matcher(haystack.getValue());
                while (matcher.find()) {
                    String evidence = matcher.group();
                    Date timestamp = null;
                    try {
                        // parse the number as a Unix timestamp
                        timestamp = new Date(TimeUnit.SECONDS.toMillis(Integer.parseInt(evidence)));
                    } catch (NumberFormatException nfe) {
                        // the number is not formatted correctly to be a timestamp. Skip it.
                        continue;
                    }
                    if (!AlertThreshold.LOW.equals(threshold)
                            && (RANGE_START.after(timestamp) || RANGE_STOP.before(timestamp))) {
                        continue;
                    }
                    LOGGER.debug("Found a match for timestamp type {}:{}", timestampType, evidence);

                    if (evidence != null && !evidence.isEmpty()) {
                        // we found something.. potentially
                        if (AlertThreshold.HIGH.equals(threshold)) {
                            Instant foundInstant = Instant.ofEpochSecond(Long.parseLong(evidence));
                            if (!(foundInstant.isAfter(ONE_YEAR_AGO)
                                    && foundInstant.isBefore(ONE_YEAR_FROM_NOW))) {
                                continue;
                            }
                        }
                        buildAlert(timestampType, evidence, haystack.getName(), timestamp).raise();
                        // do NOT break at this point.. we need to find *all* the potential
                        // timestamps in the response..
                    }
                }
            }
        }
    }

    private AlertBuilder buildAlert(
            String timestampType, String evidence, String param, Date timestamp) {
        return newAlert()
                .setName(getName() + " - " + timestampType)
                .setRisk(Alert.RISK_LOW)
                .setConfidence(Alert.CONFIDENCE_LOW)
                .setDescription(
                        Constant.messages.getString(MESSAGE_PREFIX + "desc")
                                + " - "
                                + timestampType)
                .setParam(param)
                .setOtherInfo(getExtraInfo(evidence, timestamp))
                .setSolution(Constant.messages.getString(MESSAGE_PREFIX + "soln"))
                .setReference(Constant.messages.getString(MESSAGE_PREFIX + "refs"))
                .setEvidence(evidence)
                // CWE-497: Exposure of Sensitive System Information to an Unauthorized Control
                // Sphere
                .setCweId(497)
                .setWascId(13); // WASC Id - Info leakage
    }

    @Override
    public int getPluginId() {
        return 10096;
    }

    private static String getExtraInfo(String evidence, Date timestamp) {
        String formattedDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(timestamp);
        return Constant.messages.getString(MESSAGE_PREFIX + "extrainfo", evidence, formattedDate);
    }

    @Override
    public Map<String, String> getAlertTags() {
        return ALERT_TAGS;
    }

    @Override
    public List<Alert> getExampleAlerts() {
        return List.of(
                buildAlert("Unix", "1704114087", "registeredAt", new Date(1704114087)).build());
    }

    private static boolean containsIgnoreCase(List<String> list, String test) {
        for (String element : list) {
            if (element.equalsIgnoreCase(test)) {
                return true;
            }
        }
        return false;
    }
}
