package com.dmg.notify.template;

import com.dmg.notify.common.ApiException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {{variable}} substitution. Missing variables fail fast instead of sending a half-rendered message. */
public final class TemplateRenderer {
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

    /** Column limits of the rendered message (notifications.subject / notifications.body). */
    public static final int MAX_SUBJECT = 500;
    public static final int MAX_BODY = 4000;

    private TemplateRenderer() {}

    /** Names of the variables a template references, in order of first appearance. */
    public static Set<String> variables(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null) return names;
        Matcher m = VAR.matcher(template);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    public static String render(String template, Map<String, String> vars) {
        if (template == null) return null;
        Map<String, String> values = vars == null ? Map.of() : vars;
        Set<String> missing = new LinkedHashSet<>();
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null) {
                missing.add(m.group(1));
                v = "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        if (!missing.isEmpty()) {
            throw new ApiException.Unprocessable("Missing template variables: " + String.join(", ", missing));
        }
        return out.toString();
    }
}
