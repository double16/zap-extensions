/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2012 The ZAP Development Team
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
import net.htmlparser.jericho.Source;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.core.scanner.Plugin.AlertThreshold;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.parosproxy.paros.network.HttpStatusCode;
import org.zaproxy.addon.commonlib.CommonAlertTag;
import org.zaproxy.addon.commonlib.PolicyTag;
import org.zaproxy.addon.commonlib.ResourceIdentificationUtils;
import org.zaproxy.zap.extension.pscan.PluginPassiveScanner;

public class CacheControlScanRule extends PluginPassiveScanner
        implements CommonPassiveScanRuleInfo {

    /** Prefix for internationalised messages used by this rule */
    private static final String MESSAGE_PREFIX = "pscanrules.cachecontrol.";

    private static final String CACHE_CONTROL_HEADER = HttpHeader.CACHE_CONTROL;

    private static final Map<String, String> ALERT_TAGS;

    static {
        Map<String, String> alertTags =
                new HashMap<>(CommonAlertTag.toMap(CommonAlertTag.WSTG_V42_ATHN_06_CACHE_WEAKNESS));
        alertTags.put(PolicyTag.PENTEST.getTag(), "");
        ALERT_TAGS = Collections.unmodifiableMap(alertTags);
    }

    @Override
    public void scanHttpResponseReceive(HttpMessage msg, int id, Source source) {
        if (msg.getRequestHeader().isSecure()
                && msg.getResponseBody().length() > 0
                && !HttpRequestHeader.POST.equals(msg.getRequestHeader().getMethod())
                && !ResourceIdentificationUtils.isImage(msg)) {

            if (!AlertThreshold.LOW.equals(this.getAlertThreshold())
                    && (HttpStatusCode.isRedirection(msg.getResponseHeader().getStatusCode())
                            || getHelper().isClientError(msg)
                            || getHelper().isServerError(msg)
                            || !msg.getResponseHeader().isText()
                            || ResourceIdentificationUtils.isJavaScript(msg)
                            || ResourceIdentificationUtils.isCss(msg))) {
                // Covers HTML, XML, JSON and TEXT while excluding JS & CSS
                return;
            }

            List<String> cacheControlList =
                    msg.getResponseHeader().getHeaderValues(CACHE_CONTROL_HEADER);
            String cacheControlHeaders =
                    (!cacheControlList.isEmpty()) ? cacheControlList.toString().toLowerCase() : "";

            if (cacheControlHeaders.isEmpty()
                    || // No Cache-Control header at all
                    cacheControlHeaders.indexOf("no-store") < 0
                    || cacheControlHeaders.indexOf("no-cache") < 0
                    || cacheControlHeaders.indexOf("must-revalidate") < 0) {
                this.createAlert(cacheControlHeaders).raise();
            }
        }
    }

    private AlertBuilder createAlert(String evidence) {
        if (evidence.startsWith("[") && evidence.endsWith("]")) {
            // Due to casting a Vector to a string
            // Strip so that if a single headers used the highlighting will work
            evidence = evidence.substring(1, evidence.length() - 1);
        }
        return newAlert()
                .setRisk(Alert.RISK_INFO)
                .setConfidence(Alert.CONFIDENCE_LOW)
                .setDescription(Constant.messages.getString(MESSAGE_PREFIX + "desc"))
                .setParam(CACHE_CONTROL_HEADER)
                .setSolution(Constant.messages.getString(MESSAGE_PREFIX + "soln"))
                .setReference(Constant.messages.getString(MESSAGE_PREFIX + "refs"))
                .setEvidence(evidence)
                .setCweId(525)
                .setWascId(13);
    }

    @Override
    public int getPluginId() {
        return 10015;
    }

    @Override
    public String getName() {
        return Constant.messages.getString(MESSAGE_PREFIX + "name");
    }

    @Override
    public Map<String, String> getAlertTags() {
        return ALERT_TAGS;
    }

    @Override
    public List<Alert> getExampleAlerts() {
        return List.of(createAlert("no-store, must-revalidate").build());
    }
}
