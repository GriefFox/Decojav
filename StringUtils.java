/**
 * String utilities — exercises string concat folding, char operations,
 * boolean returns, and string API calls.
 */
public class StringUtils {

    public static boolean isPalindrome(String s) {
        int lo = 0;
        int hi = s.length() - 1;
        while (lo < hi) {
            if (s.charAt(lo) != s.charAt(hi)) return false;
            lo++;
            hi--;
        }
        return true;
    }

    public static String reverse(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length() - 1; i >= 0; i--) {
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    public static int countChar(String s, char target) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == target) count++;
        }
        return count;
    }

    public static String repeat(String s, int times) {
        if (times <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    public static boolean startsWith(String s, String prefix) {
        if (prefix.length() > s.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (s.charAt(i) != prefix.charAt(i)) return false;
        }
        return true;
    }

    public static int indexOf(String s, char target) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == target) return i;
        }
        return -1;
    }

    public static String trimLeft(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return s.substring(i);
    }

    public static String join(String[] parts, String sep) {
        if (parts.length == 0) return "";
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(sep).append(parts[i]);
        }
        return sb.toString();
    }

    public static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    public static String format(String template, String value) {
        return template.replace("{}", value);
    }

    public static int parseInt(String s) {
        if (s == null || s.isEmpty()) throw new IllegalArgumentException("empty string");
        int result = 0;
        int sign = 1;
        int i = 0;
        if (s.charAt(0) == '-') {
            sign = -1;
            i = 1;
        }
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("not a digit: " + c);
            result = result * 10 + (c - '0');
        }
        return sign * result;
    }
}
