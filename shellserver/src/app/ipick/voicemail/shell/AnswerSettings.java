package app.ipick.voicemail.shell;

final class AnswerSettings {
    static final String ROUTE_MUSIC = "music";
    static final String ROUTE_SCREENING = "screening";
    static final int NO_LIMIT = 0;

    static final AnswerSettings OFF = new AnswerSettings(false, 60, ROUTE_MUSIC);

    static final AnswerSettings TEXT_ONLY = new AnswerSettings(false, 60, ROUTE_MUSIC);

    final boolean on;
    final int maxMessageSeconds;
    final String route;
    final boolean screenOff;

    AnswerSettings(boolean on, int maxMessageSeconds, String route) {
        this(on, maxMessageSeconds, route, false);
    }

    AnswerSettings(boolean on, int maxMessageSeconds, String route, boolean screenOff) {
        this.screenOff = screenOff;
        this.on = on;
        this.maxMessageSeconds = maxMessageSeconds;
        this.route = route;
    }

    static AnswerSettings parse(String wire) {
        if ("text".equals(wire)) return TEXT_ONLY;
        if (wire == null) {
            return OFF;
        }
        String[] parts = wire.trim().split(",");
        if (parts.length != 3 && parts.length != 4) {
            return OFF;
        }
        if (parts.length == 4 && !parts[3].trim().equals("0") && !parts[3].trim().equals("1")) return OFF;
        int seconds;
        try {
            seconds = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return OFF;
        }
        String route = parts[2].trim();
        if (!route.equals(ROUTE_MUSIC) && !route.equals(ROUTE_SCREENING)) {
            return OFF;
        }
        if (seconds != NO_LIMIT && (seconds < 10 || seconds > 600)) {
            return OFF;
        }
        return new AnswerSettings("1".equals(parts[0].trim()), seconds, route, parts.length == 4 && parts[3].trim().equals("1"));
    }

    String wire() {
        if (this == TEXT_ONLY) return "text";
        return (on ? "1" : "0") + "," + maxMessageSeconds + "," + route + (screenOff ? ",1" : "");
    }
}
