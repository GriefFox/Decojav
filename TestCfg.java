/**
 * Hand-written samples for testing step-2 structured control flow reconstruction.
 * Each method exercises one pattern. Compile with:
 *   javac TestCfg.java
 * Then decompile with:
 *   ./run TestCfg.class
 */
public class TestCfg {

    // ---- while loop --------------------------------------------------------

    public static int sumUpTo(int n) {
        int sum = 0;
        int i = 0;
        while (i < n) {
            sum += i;
            i++;
        }
        return sum;
    }

    // ---- for loop (same bytecode shape as while) ---------------------------

    public static int factorial(int n) {
        int result = 1;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    // ---- do-while ----------------------------------------------------------

    public static int firstPowerOfTwoAbove(int n) {
        int p = 1;
        do {
            p *= 2;
        } while (p <= n);
        return p;
    }

    // ---- if (no else) ------------------------------------------------------

    public static int abs(int x) {
        if (x < 0) {
            x = -x;
        }
        return x;
    }

    // ---- if-else -----------------------------------------------------------

    public static String sign(int x) {
        if (x > 0) {
            return "positive";
        } else {
            return "non-positive";
        }
    }

    // ---- nested if-else ----------------------------------------------------

    public static String classify(int x) {
        if (x > 0) {
            return "positive";
        } else if (x < 0) {
            return "negative";
        } else {
            return "zero";
        }
    }

    // ---- break inside loop -------------------------------------------------

    public static int firstDivisibleBy(int[] arr, int divisor) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] % divisor == 0) {
                return arr[i];
            }
        }
        return -1;
    }

    // ---- early return inside branch ----------------------------------------

    public static int safeDiv(int a, int b) {
        if (b == 0) {
            return 0;
        }
        return a / b;
    }
}
