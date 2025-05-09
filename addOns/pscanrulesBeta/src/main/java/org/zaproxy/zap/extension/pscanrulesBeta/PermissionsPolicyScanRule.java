/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2019 The ZAP Development Team
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
package org.zaproxy.zap.extension.pscanrulesBeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.htmlparser.jericho.Source;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.core.scanner.Plugin.AlertThreshold;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpStatusCode;
import org.zaproxy.addon.commonlib.CommonAlertTag;
import org.zaproxy.addon.commonlib.PolicyTag;
import org.zaproxy.addon.commonlib.ResourceIdentificationUtils;
import org.zaproxy.zap.extension.pscan.PluginPassiveScanner;

/**
 * Permissions Policy Header Missing passive scan rule
 * https://github.com/zaproxy/zaproxy/issues/4885
 */
public class PermissionsPolicyScanRule extends PluginPassiveScanner
        implements CommonPassiveScanRuleInfo {

    private static final String PERMISSIONS_POLICY_HEADER = "Permissions-Policy";
    private static final String DEPRECATED_HEADER = "Feature-Policy";
    private static final String MESSAGE_PREFIX = "pscanbeta.permissionspolicymissing.";
    private static final Logger LOGGER = LogManager.getLogger(PermissionsPolicyScanRule.class);
    private static final Map<String, String> ALERT_TAGS;

    static {
        Map<String, String> alertTags =
                new HashMap<>(
                        CommonAlertTag.toMap(
                                CommonAlertTag.OWASP_2021_A01_BROKEN_AC,
                                CommonAlertTag.OWASP_2017_A05_BROKEN_AC));
        alertTags.put(PolicyTag.PENTEST.getTag(), "");
        alertTags.put(PolicyTag.QA_STD.getTag(), "");
        ALERT_TAGS = Collections.unmodifiableMap(alertTags);
    }

    private static final int PLUGIN_ID = 10063;

    @Override
    public void scanHttpResponseReceive(HttpMessage httpMessage, int id, Source source) {
        long start = System.currentTimeMillis();

        if (!httpMessage.getResponseHeader().isHtml()
                && !ResourceIdentificationUtils.isJavaScript(httpMessage)) {
            return;
        }
        if (HttpStatusCode.isRedirection(httpMessage.getResponseHeader().getStatusCode())
                && !AlertThreshold.LOW.equals(this.getAlertThreshold())) {
            return;
        }

        // Feature-Policy is supported by Chrome 60+, Firefox 65+, Opera 47+, but not by Internet
        // Exploder or Safari
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Feature-Policy#Browser_compatibility
        List<String> featurePolicyOptions =
                httpMessage.getResponseHeader().getHeaderValues(DEPRECATED_HEADER);
        List<String> permissionPolicyOptions =
                httpMessage.getResponseHeader().getHeaderValues(PERMISSIONS_POLICY_HEADER);
        if (!featurePolicyOptions.isEmpty()) {
            buildDeprecatedAlert().raise();
        } else if (permissionPolicyOptions.isEmpty()) {
            buildMissingAlert().raise();
        }

        LOGGER.debug("\tScan of record {} took {} ms", id, System.currentTimeMillis() - start);
    }

    @Override
    public int getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return Constant.messages.getString(MESSAGE_PREFIX + "name");
    }

    @Override
    public Map<String, String> getAlertTags() {
        return ALERT_TAGS;
    }

    private static String getAlertAttribute(String key) {
        return Constant.messages.getString(MESSAGE_PREFIX + key);
    }

    private AlertBuilder getBuilder() {
        return newAlert().setRisk(Alert.RISK_LOW).setConfidence(Alert.CONFIDENCE_MEDIUM);
    }

    private AlertBuilder buildDeprecatedAlert() {
        return getBuilder()
                .setName(getAlertAttribute("deprecated.name"))
                .setDescription(getAlertAttribute("deprecated.desc"))
                .setSolution(getAlertAttribute("deprecated.soln"))
                .setReference(getAlertAttribute("deprecated.refs"))
                .setEvidence(DEPRECATED_HEADER)
                .setCweId(16) // CWE-16: Configuration
                .setWascId(15) // WASC-15: Application Misconfiguration
                .setAlertRef(PLUGIN_ID + "-2");
    }

    private AlertBuilder buildMissingAlert() {
        return getBuilder()
                .setDescription(getAlertAttribute("desc"))
                .setSolution(getAlertAttribute("soln"))
                .setReference(getAlertAttribute("refs"))
                .setCweId(693) // CWE-693: Protection Mechanism Failure
                .setWascId(15) // WASC-15: Application Misconfiguration
                .setAlertRef(PLUGIN_ID + "-1");
    }

    @Override
    public List<Alert> getExampleAlerts() {
        List<Alert> alerts = new ArrayList<>();
        alerts.add(buildMissingAlert().build());
        alerts.add(buildDeprecatedAlert().build());
        return alerts;
    }
}
