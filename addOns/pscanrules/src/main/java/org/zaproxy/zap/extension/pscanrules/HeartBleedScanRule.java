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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.htmlparser.jericho.Source;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.addon.commonlib.CommonAlertTag;
import org.zaproxy.addon.commonlib.PolicyTag;
import org.zaproxy.zap.extension.pscan.PluginPassiveScanner;

/**
 * A class to passively scan responses for HTTP header signatures that indicate that the web server
 * is vulnerable to the HeartBleed OpenSSL vulnerability
 *
 * @author 70pointer@gmail.com
 */
public class HeartBleedScanRule extends PluginPassiveScanner implements CommonPassiveScanRuleInfo {

    /**
     * a pattern to identify the version reported in the header. This works for Apache2 (subject to
     * the reported version containing back-ported security fixes). There is no equivalent way to
     * check Nginx, so don't even try. The only way to be absolutely sure is to exploit it :)
     */
    private static Pattern openSSLversionPattern =
            Pattern.compile("Server:.*?(OpenSSL/([0-9.]+[a-z-0-9]+))", Pattern.CASE_INSENSITIVE);

    /** vulnerable versions, courtesy of https://nvd.nist.gov/vuln/detail/CVE-2014-0160 */
    static String[] openSSLvulnerableVersions = {
        "1.0.1-Beta1",
        "1.0.1-Beta2",
        "1.0.1-Beta3",
        "1.0.1",
        "1.0.1a",
        "1.0.1b",
        "1.0.1c",
        "1.0.1d",
        "1.0.1e",
        "1.0.1f",
        "1.0.2-beta" // does not come from the page above, but reported elsewhere to be vulnerable.
    };

    /** Prefix for internationalized messages used by this rule */
    private static final String MESSAGE_PREFIX = "pscanrules.heartbleed.";

    private static final String CVE = "CVE-2014-0160";
    private static final Map<String, String> ALERT_TAGS;

    static {
        Map<String, String> alertTags = new HashMap<>();
        alertTags.putAll(
                CommonAlertTag.toMap(
                        CommonAlertTag.OWASP_2021_A06_VULN_COMP,
                        CommonAlertTag.OWASP_2017_A09_VULN_COMP,
                        CommonAlertTag.WSTG_V42_CRYP_01_TLS));
        alertTags.put(PolicyTag.PENTEST.getTag(), "");
        CommonAlertTag.putCve(alertTags, CVE);
        ALERT_TAGS = Collections.unmodifiableMap(alertTags);
    }

    @Override
    public String getName() {
        return Constant.messages.getString(MESSAGE_PREFIX + "name");
    }

    /**
     * scans the HTTP response for signatures that might indicate the Heartbleed OpenSSL
     * vulnerability
     *
     * @param msg
     * @param id
     * @param source unused
     */
    @Override
    public void scanHttpResponseReceive(HttpMessage msg, int id, Source source) {
        // get the body contents as a String, so we can match against it
        String responseHeaders = msg.getResponseHeader().getHeadersAsString();

        Matcher matcher = openSSLversionPattern.matcher(responseHeaders);
        while (matcher.find()) {
            String fullVersionString = matcher.group(1); // get the full string e.g. OpenSSL/1.0.1e
            String versionNumber = matcher.group(2); // get the version e.g. 1.0.1e

            // if the version matches any of the known vulnerable versions, raise an alert.
            for (String openSSLvulnerableVersion : openSSLvulnerableVersions) {
                if (versionNumber.equalsIgnoreCase(openSSLvulnerableVersion)) {
                    buildAlert(fullVersionString).raise();
                    return;
                }
            }
        }
    }

    private AlertBuilder buildAlert(String fullVersionString) {
        // Suspicious, but not a warning, because the reported version could have a
        // security back-port.
        return newAlert()
                .setRisk(Alert.RISK_HIGH)
                .setConfidence(Alert.CONFIDENCE_LOW)
                .setDescription(Constant.messages.getString(MESSAGE_PREFIX + "desc"))
                .setOtherInfo(
                        Constant.messages.getString(
                                MESSAGE_PREFIX + "extrainfo", fullVersionString))
                .setSolution(Constant.messages.getString(MESSAGE_PREFIX + "soln"))
                .setReference(Constant.messages.getString(MESSAGE_PREFIX + "refs"))
                .setEvidence(fullVersionString)
                .setCweId(119) // CWE 119: Failure to Constrain Operations within the Bounds of a
                // Memory Buffer
                .setWascId(20); // WASC-20: Improper Input Handling
    }

    @Override
    public int getPluginId() {
        return 10034;
    }

    @Override
    public Map<String, String> getAlertTags() {
        return ALERT_TAGS;
    }

    @Override
    public List<Alert> getExampleAlerts() {
        return List.of(buildAlert("OpenSSL/1.0.1e").build());
    }
}
