/**
 * Classic algorithms — exercises loops, recursion, nested control flow,
 * early returns, and numeric types.
 */
public class Algorithms {

    // ---- searching ---------------------------------------------------------

    public static int linearSearch(int[] arr, int target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == target) return i;
        }
        return -1;
    }

    public static int binarySearch(int[] arr, int target) {
        int lo = 0;
        int hi = arr.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            if (arr[mid] == target) return mid;
            if (arr[mid] < target)  lo = mid + 1;
            else                    hi = mid - 1;
        }
        return -1;
    }

    // ---- sorting -----------------------------------------------------------

    public static void bubbleSort(int[] arr) {
        int n = arr.length;
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < n - 1 - i; j++) {
                if (arr[j] > arr[j + 1]) {
                    int tmp = arr[j];
                    arr[j]     = arr[j + 1];
                    arr[j + 1] = tmp;
                }
            }
        }
    }

    // ---- recursion ---------------------------------------------------------

    public static long fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }

    public static long fibIterative(int n) {
        if (n <= 1) return n;
        long a = 0;
        long b = 1;
        for (int i = 2; i <= n; i++) {
            long tmp = a + b;
            a = b;
            b = tmp;
        }
        return b;
    }

    public static int gcd(int a, int b) {
        while (b != 0) {
            int tmp = b;
            b = a % b;
            a = tmp;
        }
        return a;
    }

    // ---- number checks -----------------------------------------------------

    public static boolean isPrime(int n) {
        if (n < 2) return false;
        if (n == 2) return true;
        if (n % 2 == 0) return false;
        for (int i = 3; i * i <= n; i += 2) {
            if (n % i == 0) return false;
        }
        return true;
    }

    public static int countPrimes(int limit) {
        int count = 0;
        for (int i = 2; i <= limit; i++) {
            if (isPrime(i)) count++;
        }
        return count;
    }

    // ---- array utilities ---------------------------------------------------

    public static int max(int[] arr) {
        if (arr.length == 0) throw new IllegalArgumentException("empty array");
        int best = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > best) best = arr[i];
        }
        return best;
    }

    public static double average(int[] arr) {
        if (arr.length == 0) return 0.0;
        long sum = 0;
        for (int v : arr) sum += v;
        return (double) sum / arr.length;
    }

    public static void reverse(int[] arr) {
        int lo = 0;
        int hi = arr.length - 1;
        while (lo < hi) {
            int tmp = arr[lo];
            arr[lo] = arr[hi];
            arr[hi] = tmp;
            lo++;
            hi--;
        }
    }
}
