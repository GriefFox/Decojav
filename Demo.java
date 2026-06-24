public class Demo {

    // GETSTATIC / INVOKEVIRTUAL (void) -> IrExprStmt
    // LDC, INVOKESTATIC
    public static void greet(String name) {
        String msg = "Hello, " + name;
        System.out.println(msg);
    }

    // GETFIELD / PUTFIELD
    // NEW + INVOKESPECIAL <init> with arg
    static int counter = 0;
    int id;

    public Demo(int id) {
        this.id = id;
        Demo.counter += 1;
    }

    public int getId() {
        return id;
    }

    // INVOKESTATIC returning value, arithmetic
    public static int square(int x) {
        return x * x;
    }

    public static int sumOfSquares(int a, int b) {
        return square(a) + square(b);
    }

    // NEWARRAY, IASTORE, IALOAD, ARRAYLENGTH
    public static int[] buildRange(int n) {
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) {
            arr[i] = i * i;
        }
        return arr;
    }

    public static int sumArray(int[] arr) {
        int total = 0;
        for (int i = 0; i < arr.length; i++) {
            total += arr[i];
        }
        return total;
    }

    // ANEWARRAY, AASTORE, AALOAD
    public static String[] buildNames(String a, String b, String c) {
        String[] names = new String[3];
        names[0] = a;
        names[1] = b;
        names[2] = c;
        return names;
    }

    // INSTANCEOF, CHECKCAST, INVOKEVIRTUAL on result
    public static String describe(Object obj) {
        if (obj instanceof String s) {
            return "string:" + s.toLowerCase();
        }
        return "other";
    }

    // ATHROW, NEW + <init> with String arg
    public static void requirePositive(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("expected positive, got " + n);
        }
    }

    // INVOKEVIRTUAL chained, DUP patterns
    public static String buildMessage(int code, String text) {
        return new StringBuilder()
                .append(code)
                .append(": ")
                .append(text)
                .toString();
    }
}
