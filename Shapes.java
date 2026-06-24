/**
 * OOP patterns — exercises constructors with args, instance methods, fields,
 * this-calls, and instanceof checks.
 */
public class Shapes {

    // ---- Point -------------------------------------------------------------

    static class Point {
        final double x;
        final double y;

        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }

        double distanceTo(Point other) {
            double dx = this.x - other.x;
            double dy = this.y - other.y;
            return Math.sqrt(dx * dx + dy * dy);
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ")";
        }
    }

    // ---- Shape hierarchy ---------------------------------------------------

    static abstract class Shape {
        final String color;

        Shape(String color) {
            this.color = color;
        }

        abstract double area();
        abstract double perimeter();

        String describe() {
            return color + " " + getClass().getSimpleName()
                    + " area=" + area()
                    + " perimeter=" + perimeter();
        }
    }

    static class Circle extends Shape {
        final double radius;

        Circle(String color, double radius) {
            super(color);
            this.radius = radius;
        }

        @Override
        public double area() {
            return Math.PI * radius * radius;
        }

        @Override
        public double perimeter() {
            return 2 * Math.PI * radius;
        }
    }

    static class Rectangle extends Shape {
        final double width;
        final double height;

        Rectangle(String color, double width, double height) {
            super(color);
            this.width  = width;
            this.height = height;
        }

        @Override
        public double area() {
            return width * height;
        }

        @Override
        public double perimeter() {
            return 2 * (width + height);
        }

        boolean isSquare() {
            return width == height;
        }
    }

    // ---- utility methods ---------------------------------------------------

    static Shape largest(Shape[] shapes) {
        if (shapes.length == 0) throw new IllegalArgumentException("empty");
        Shape best = shapes[0];
        for (int i = 1; i < shapes.length; i++) {
            if (shapes[i].area() > best.area()) best = shapes[i];
        }
        return best;
    }

    static double totalArea(Shape[] shapes) {
        double sum = 0.0;
        for (Shape s : shapes) sum += s.area();
        return sum;
    }

    static int countCircles(Shape[] shapes) {
        int count = 0;
        for (Shape s : shapes) {
            if (s instanceof Circle) count++;
        }
        return count;
    }

    static void scale(Rectangle r, double factor) {
        // Can't mutate finals, so demonstrate method call chain
        System.out.println("scaled: " + r.width * factor + " x " + r.height * factor);
    }

    static String classify(Shape s) {
        if (s instanceof Circle c) {
            if (c.radius < 1.0) return "tiny circle";
            if (c.radius < 10.0) return "small circle";
            return "large circle";
        }
        if (s instanceof Rectangle r) {
            if (r.isSquare()) return "square";
            return "rectangle";
        }
        return "unknown shape";
    }
}
