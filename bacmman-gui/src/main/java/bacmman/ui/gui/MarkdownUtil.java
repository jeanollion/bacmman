package bacmman.ui.gui;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownUtil {
    public static String convertMarkdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        String[] lines = markdown.split("\n");
        java.util.List<ListInfo> listInfoStack = new ArrayList<>();
        boolean inList = false;

        for (String line : lines) {
            int leadingSpaces = countLeadingSpaces(line);
            String trimmedLine = line.trim();

            if (trimmedLine.startsWith("# ")) {
                closeOpenLists(html, listInfoStack);
                inList = false;
                html.append("<h1>").append(processInlineFormatting(trimmedLine.substring(2))).append("</h1>\n");
            } else if (trimmedLine.startsWith("## ")) {
                closeOpenLists(html, listInfoStack);
                inList = false;
                html.append("<h2>").append(processInlineFormatting(trimmedLine.substring(3))).append("</h2>\n");
            } else if (trimmedLine.startsWith("### ")) {
                closeOpenLists(html, listInfoStack);
                inList = false;
                html.append("<h3>").append(processInlineFormatting(trimmedLine.substring(4))).append("</h3>\n");
            } else if (trimmedLine.startsWith("#### ")) {
                closeOpenLists(html, listInfoStack);
                inList = false;
                html.append("<h4>").append(processInlineFormatting(trimmedLine.substring(5))).append("</h4>\n");
            } else if (trimmedLine.startsWith("##### ")) {
                closeOpenLists(html, listInfoStack);
                inList = false;
                html.append("<h5>").append(processInlineFormatting(trimmedLine.substring(6))).append("</h5>\n");
            } else if (trimmedLine.matches("- .+") || trimmedLine.matches("\\d+\\. .+")) {
                boolean isOrdered = trimmedLine.matches("\\d+\\. .+");
                handleListItem(html, line, leadingSpaces, listInfoStack, isOrdered);
                inList = true;
            } else if (trimmedLine.isEmpty() && inList) {
                // Do nothing for empty lines within a list
                continue;
            } else {
                closeOpenLists(html, listInfoStack);
                inList = false;
                if (!trimmedLine.isEmpty()) {
                    html.append("<p>").append(processInlineFormatting(trimmedLine)).append("</p>\n");
                }
            }
        }

        // Close any open lists
        closeOpenLists(html, listInfoStack);

        return html.toString();
    }

    private static void handleListItem(StringBuilder html, String line, int leadingSpaces, java.util.List<ListInfo> listInfoStack, boolean isOrdered) {
        int currentDepth = leadingSpaces / 2;

        // Close lists that are deeper than the current depth
        while (!listInfoStack.isEmpty() && listInfoStack.get(listInfoStack.size() - 1).depth > currentDepth) {
            ListInfo lastListInfo = listInfoStack.remove(listInfoStack.size() - 1);
            html.append(lastListInfo.isOrdered ? "</ol>\n" : "</ul>\n");
        }

        // Open a new list if the current depth is greater
        if (listInfoStack.isEmpty() || listInfoStack.get(listInfoStack.size() - 1).depth < currentDepth) {
            html.append(isOrdered ? "<ol>\n" : "<ul>\n");
            listInfoStack.add(new ListInfo(currentDepth, isOrdered));
        } else if (listInfoStack.get(listInfoStack.size() - 1).isOrdered != isOrdered) { // close the last list and open a new one if the type has changed
            ListInfo lastListInfo = listInfoStack.remove(listInfoStack.size() - 1);
            html.append(lastListInfo.isOrdered ? "</ol>\n" : "</ul>\n");
            listInfoStack.add(new ListInfo(currentDepth, isOrdered));
            html.append(isOrdered ? "<ol>\n" : "<ul>\n");
        }

        html.append("<li>").append(processInlineFormatting(line.trim().replaceFirst("^\\d+\\. ", "").replaceFirst("^- ", ""))).append("</li>\n");
    }

    private static void closeOpenLists(StringBuilder html, java.util.List<ListInfo> listInfoStack) {
        while (!listInfoStack.isEmpty()) {
            ListInfo lastListInfo = listInfoStack.remove(listInfoStack.size() - 1);
            html.append(lastListInfo.isOrdered ? "</ol>\n" : "</ul>\n");
        }
    }

    private static int countLeadingSpaces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private static String processInlineFormatting(String text) {
        // Process bold **text**
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        // Process italic *text*
        text = text.replaceAll("\\*(.+?)\\*", "<em>$1</em>");
        // Process links [text](url)
        Pattern linkPattern = Pattern.compile("\\[([^\\]]+)\\]\\(([^\\)]+)\\)");
        Matcher matcher = linkPattern.matcher(text);
        while (matcher.find()) {
            String linkText = matcher.group(1);
            String linkUrl = matcher.group(2);
            text = text.replaceFirst("\\[([^\\]]+)\\]\\(([^\\)]+)\\)", "<a href=\"" + linkUrl + "\">" + linkText + "</a>");
        }
        return text;
    }

    static class ListInfo {
        int depth;
        boolean isOrdered;

        ListInfo(int depth, boolean isOrdered) {
            this.depth = depth;
            this.isOrdered = isOrdered;
        }
    }
}
