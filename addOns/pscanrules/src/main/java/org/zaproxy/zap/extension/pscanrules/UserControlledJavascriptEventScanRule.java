/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2013 The ZAP Development Team
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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.htmlparser.jericho.Attribute;
import net.htmlparser.jericho.Attributes;
import net.htmlparser.jericho.Element;
import net.htmlparser.jericho.Source;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.network.HtmlParameter;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.addon.commonlib.CommonAlertTag;
import org.zaproxy.addon.commonlib.PolicyTag;
import org.zaproxy.zap.extension.pscan.PluginPassiveScanner;

/**
 * Port for the Watcher passive scanner (http://websecuritytool.codeplex.com/) rule {@code
 * CasabaSecurity.Web.Watcher.Checks.CheckPasvUserControlledJavascriptEvent}
 */
public class UserControlledJavascriptEventScanRule extends PluginPassiveScanner
        implements CommonPassiveScanRuleInfo {

    private static final String[] JAVASCRIPT_EVENTS =
            new String[] {
                "onabort",
                "onbeforeunload",
                "onblur",
                "onchange",
                "onclick",
                "oncontextmenu",
                "ondblclick",
                "ondrag",
                "ondragend",
                "ondragenter",
                "ondragleave",
                "ondragover",
                "ondragstart",
                "ondrop",
                "onerror",
                "onfocus",
                "onhashchange",
                "onkeydown",
                "onkeypress",
                "onkeyup",
                "onload",
                "onmessage",
                "onmousedown",
                "onmousemove",
                "onmouseout",
                "onmouseover",
                "onmouseup",
                "onmousewheel",
                "onoffline",
                "ononline",
                "onpopstate",
                "onreset",
                "onresize",
                "onscroll",
                "onselect",
                "onstorage",
                "onsubmit",
                "onunload"
            };

    /** Prefix for internationalized messages used by this rule */
    private static final String MESSAGE_PREFIX = "pscanrules.usercontrolledjavascriptevent.";

    private static final Map<String, String> ALERT_TAGS;

    static {
        Map<String, String> alertTags =
                new HashMap<>(
                        CommonAlertTag.toMap(
                                CommonAlertTag.OWASP_2021_A03_INJECTION,
                                CommonAlertTag.OWASP_2017_A01_INJECTION));
        alertTags.put(PolicyTag.PENTEST.getTag(), "");
        ALERT_TAGS = Collections.unmodifiableMap(alertTags);
    }

    @Override
    public String getName() {
        return Constant.messages.getString(MESSAGE_PREFIX + "name");
    }

    @Override
    public void scanHttpResponseReceive(HttpMessage msg, int id, Source source) {
        if (!getHelper().isPage200(msg)) {
            return;
        }

        if (!isResponseHTML(msg)) {
            return;
        }

        Set<HtmlParameter> params = new TreeSet<>(msg.getFormParams());
        params.addAll(msg.getUrlParams());
        if (params.isEmpty()) {
            return;
        }

        List<Element> htmlElements = source.getAllElements();
        for (Element htmlElement : htmlElements) {
            Attributes attributes = htmlElement.getAttributes();
            if (attributes == null) {
                continue;
            }

            for (Attribute attribute : attributes) {
                if (Arrays.binarySearch(JAVASCRIPT_EVENTS, attribute.getName().toLowerCase())
                        >= 0) {
                    for (HtmlParameter param : params) {
                        if (param.getValue() != null && param.getValue().length() > 0) {
                            checkJavascriptEvent(msg, id, htmlElement, attribute, param);
                        }
                    }
                }
            }
        }
    }

    private void checkJavascriptEvent(
            HttpMessage msg,
            int id,
            Element htmlElement,
            Attribute attribute,
            HtmlParameter param) {
        // Try some rudimentary parsing of the Javascript event
        // so we can find the user-input.
        String[] split = attribute.getValue().split("[;=,:]");
        for (String s : split) {
            if (s.equalsIgnoreCase(param.getValue())) {
                buildAlert(
                                msg.getRequestHeader().getURI().toString(),
                                attribute.getName(),
                                attribute.getValue(),
                                param)
                        .raise();
            }
        }
    }

    // TODO: Fix up to support other variations of text/html.
    // FIX: This will match Atom and RSS feeds now, which set text/html but
    // use &lt;?xml&gt; in content

    // TODO: these methods have been extracted from CharsetMismatchScanner
    // I think we should create helper methods for them
    private static boolean isResponseHTML(HttpMessage message) {
        String contentType = message.getResponseHeader().getHeader(HttpHeader.CONTENT_TYPE);
        if (contentType == null) {
            return false;
        }

        return contentType.indexOf("text/html") != -1
                || contentType.indexOf("application/xhtml+xml") != -1
                || contentType.indexOf("application/xhtml") != -1;
    }

    private AlertBuilder buildAlert(
            String url, String attribute, String attributeValue, HtmlParameter param) {
        return newAlert()
                .setRisk(Alert.RISK_INFO)
                .setConfidence(Alert.CONFIDENCE_LOW)
                .setDescription(Constant.messages.getString(MESSAGE_PREFIX + "desc"))
                .setParam(param.getName())
                .setOtherInfo(
                        Constant.messages.getString(
                                MESSAGE_PREFIX + "extrainfo",
                                url,
                                attribute,
                                attributeValue,
                                param.getValue()))
                .setSolution(Constant.messages.getString(MESSAGE_PREFIX + "soln"))
                .setReference(Constant.messages.getString(MESSAGE_PREFIX + "refs"))
                .setCweId(20) // CWE-20: Improper Input Validation
                .setWascId(20); // WASC-20: Improper Input Handling
    }

    @Override
    public int getPluginId() {
        return 10043;
    }

    @Override
    public Map<String, String> getAlertTags() {
        return ALERT_TAGS;
    }

    @Override
    public List<Alert> getExampleAlerts() {
        return List.of(
                buildAlert(
                                "http://example.com/i.php?place=moon&name=Foo",
                                "onerror",
                                "foo",
                                new HtmlParameter(HtmlParameter.Type.url, "name", "foo"))
                        .build());
    }
}
