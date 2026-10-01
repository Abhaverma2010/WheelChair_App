package com.wheelchair.bluetoothswitch;

import java.util.Locale;

/**
 * Maps free-form recognized speech text to one of the five single-character
 * wheelchair commands (F/B/L/R/S), tolerating common phrasing variations and
 * capitalization. Returns 0 (no match) if nothing recognizable is found.
 */
public final class VoiceCommandMapper {

    private VoiceCommandMapper() {
    }

    // Matching uses "contains", so order within each array doesn't matter.
    // map() below checks STOP first, ahead of F/B/L/R: a phrase that
    // mentions "stop" alongside a direction (e.g. "stop going forward")
    // must stop the wheelchair, not move it.
    private static final String[] FORWARD_PHRASES = {
            "go forward", "move forward", "forward"
    };
    private static final String[] BACKWARD_PHRASES = {
            "go backward", "move backward", "backward", "reverse"
    };
    private static final String[] LEFT_PHRASES = {
            "turn left", "go left", "left"
    };
    private static final String[] RIGHT_PHRASES = {
            "turn right", "go right", "right"
    };
    private static final String[] STOP_PHRASES = {
            "stop wheelchair", "halt", "stop"
    };

    /**
     * @param recognizedText raw text from the speech recognizer
     * @return 'F', 'B', 'L', 'R', 'S', or 0 if no command phrase was matched
     */
    public static char map(String recognizedText) {
        if (recognizedText == null) {
            return 0;
        }
        String normalized = recognizedText.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return 0;
        }

        if (containsAny(normalized, STOP_PHRASES)) {
            return 'S';
        }
        if (containsAny(normalized, FORWARD_PHRASES)) {
            return 'F';
        }
        if (containsAny(normalized, BACKWARD_PHRASES)) {
            return 'B';
        }
        if (containsAny(normalized, LEFT_PHRASES)) {
            return 'L';
        }
        if (containsAny(normalized, RIGHT_PHRASES)) {
            return 'R';
        }
        return 0;
    }

    public static String commandLabel(char command) {
        switch (command) {
            case 'F': return "Forward";
            case 'B': return "Backward";
            case 'L': return "Left";
            case 'R': return "Right";
            case 'S': return "Stopped";
            default: return "Unknown";
        }
    }

    private static boolean containsAny(String text, String[] phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
