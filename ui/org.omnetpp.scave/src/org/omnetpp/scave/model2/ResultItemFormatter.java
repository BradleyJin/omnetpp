/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.model2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections.set.ListOrderedSet;
import org.apache.commons.lang3.text.StrBuilder;
import org.omnetpp.scave.engine.ResultItem;
import org.omnetpp.scave.engine.ResultItemFields;
import org.omnetpp.scave.engine.Scave;

/**
 * Formatting tool for generating legend labels and
 * identifiers for result items.
 * <p>
 * FileFormat string may contain to fields of the result item
 * by "${fieldname}" syntax. Use "\$" to quote a '$' if needed.
 * Accepted field names are defined by {@link FilterUtil.getFieldNames()}.
 * Use ${index} to refer to the index of the item in the collection.
 *
 * @author tomi
 */
public class ResultItemFormatter {

    private static final String fieldSpecifierRE = "(?<!\\\\)\\$\\{([a-zA-Z-_]+)\\}";
    private static final Pattern fsPattern = Pattern.compile(fieldSpecifierRE);

    private static final Map<String,IResultItemFormatter> formatters;

    static {
        formatters = new HashMap<String,IResultItemFormatter>();
        formatters.put("title_or_name", new TitleOrNameFormatter());
        for (String field : ResultItemFields.getFieldNames().toArray()) {
            IResultItemFormatter formatter = null;
            if (field.equals(Scave.FILE))
                formatter = new FileNameFormatter();
            else if (field.equals(Scave.RUN))
                formatter = new RunNameFormatter();
            else if (field.equals(Scave.MODULE))
                formatter = new ModuleNameFormatter();
            else if (field.equals(Scave.NAME))
                formatter = new DataNameFormatter();
            else
                formatter = new RunAttributeFormatter(field);
            if (formatter != null)
                formatters.put(field, formatter);
        }
    }

    public static String[] formatResultItems(String format, ResultItem[] items) {
        return formatResultItems(format, Arrays.asList(items));
    }

    public static String[] formatResultItems(String format, Collection<? extends ResultItem> items) {
        Object[] formatObjects = parseFormatString(format);
        String[] names = new String[items.size()];
        int i = 0;
        for (ResultItem item : items)
            names[i++] = formatResultItem(formatObjects, item);
        return names;
    }

    public static String formatResultItem(String format, ResultItem item) {
        return formatResultItem(parseFormatString(format), item);
    }

    private static String formatResultItem(Object[] format, ResultItem item) {
        StrBuilder sb = new StrBuilder();
        for (Object formatObj : format) {
            format(formatObj, item, sb);
        }
        return sb.toString();
    }

    public static String formatMultipleResultItem(String format, ResultItem[] items) {
        return formatMultipleResultItem(parseFormatString(format), Arrays.asList(items));
    }

    private static String formatMultipleResultItem(Object[] format, Collection<? extends ResultItem> items) {
        StrBuilder sb = new StrBuilder();
        for (Object formatObj : format) {
            format(formatObj, items, sb);
        }
        return sb.toString();
    }

    private static void format(Object formatObj, ResultItem item, StrBuilder sb) {
        if (formatObj instanceof String)
            sb.append(formatObj);
        else if (formatObj instanceof IResultItemFormatter)
            sb.append(((IResultItemFormatter)formatObj).format(item));
    }

    @SuppressWarnings("unchecked")
    private static void format(Object formatObj, Collection<? extends ResultItem> items, StrBuilder sb) {
        if (formatObj instanceof String)
            sb.append(formatObj);
        else if (formatObj instanceof IResultItemFormatter) {
            Set<String> strings = new ListOrderedSet();
            IResultItemFormatter formatter = (IResultItemFormatter)formatObj;
            for (ResultItem item : items) {
                String str = formatter.format(item);
                boolean added = strings.add(str);
                if (added && strings.size() > 3) {
                    strings.remove(str);
                    strings.add("...");
                    break;
                }
            }
            if (strings.size() > 1) sb.append('{');
            sb.appendWithSeparators(strings, ",");
            if (strings.size() > 1) sb.append('}');
        }
    }

    public static Object[] parseFormatString(String format) {
        List<Object> formatObjs = new ArrayList<Object>();
        Matcher matcher = fsPattern.matcher(format);
        int start = 0, len = format.length();
        while (start < len) {
            if (matcher.find(start)) {
                // add previous characters as fixed string
                if (matcher.start() != start)
                    formatObjs.add(unquoteDollar(format.substring(start, matcher.start())));

                String fieldName = matcher.group(1);
                IResultItemFormatter formatter = getFormatter(fieldName);
                if (formatter != null)
                    formatObjs.add(formatter);
                else
                    formatObjs.add(format.substring(matcher.start(), matcher.end()));
                start = matcher.end();
            }
            else {
                // No more valid format specifiers.
                // The rest of the string is fixed text
                formatObjs.add(unquoteDollar(format.substring(start)));
                break;
            }
        }
        return formatObjs.toArray();
    }

    private static String unquoteDollar(String str) {
        return str.replace("\\$", "$");
    }

    public static boolean isPlainFormat(String format) {
        return !fsPattern.matcher(format).find();
    }

    private static IResultItemFormatter getFormatter(String field) {
        if ("index".equals(field))
            return new IndexFormatter();
        else {
            IResultItemFormatter resultItemFormatter = formatters.get(field);

            if (resultItemFormatter != null)
                return resultItemFormatter;
            else
                return new ResultItemAttributeFormatter(field);
        }
    }

    interface IResultItemFormatter
    {
        String format(ResultItem item);
    }

    static class FileNameFormatter implements IResultItemFormatter
    {
        public String format(ResultItem item) {
            return item.getFileRun().getFile().getFilePath();
        }
    }

    static class RunNameFormatter implements IResultItemFormatter
    {
        public String format(ResultItem item) {
            return item.getFileRun().getRun().getRunName();
        }
    }

    static class ModuleNameFormatter implements IResultItemFormatter
    {
        public String format(ResultItem item) {
            return item.getModuleName();
        }
    }

    static class DataNameFormatter implements IResultItemFormatter
    {
        public String format(ResultItem item) {
            return item.getName();
        }
    }

    static class RunAttributeFormatter implements IResultItemFormatter
    {
        private String attrName;

        public RunAttributeFormatter(String attrName) {
            this.attrName = attrName;
        }

        public String format(ResultItem item) {
            return item.getFileRun().getRun().getAttribute(attrName);
        }
    }

    static class ResultItemAttributeFormatter implements IResultItemFormatter
    {
        private String attrName;

        public ResultItemAttributeFormatter(String attrName) {
            this.attrName = attrName;
        }

        public String format(ResultItem item) {
            String value = item.getAttribute(attrName);
            if (value == null)
                return "";
            else
                return value;
        }
    }

    static class IndexFormatter implements IResultItemFormatter
    {
        int index;

        public String format(ResultItem item) {
            return String.valueOf(index++);
        }
    }

    static class TitleOrNameFormatter implements IResultItemFormatter
    {
        public String format(ResultItem item) {
            String title = item.getAttribute("title");
            return title != null ? title : item.getName();
        }
    }
}
