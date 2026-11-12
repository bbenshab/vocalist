package com.bbenshab.vocalist.brain;

/** One turn of a conversation, as remembered and sent to the model. */
public record Line(String role, String text) {

    public static Line user(String text) {
        return new Line("user", text);
    }

    public static Line assistant(String text) {
        return new Line("assistant", text);
    }

    public static Line system(String text) {
        return new Line("system", text);
    }
}
