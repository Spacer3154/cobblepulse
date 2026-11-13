package fr.cobblepulse.league;

/** Les trois rangs de la Ligue. */
public enum PostKind {
    CHAMPION("Champion d'Arène", 8, 1),
    ELITE("Conseil 4", 4, 2),
    MASTER("Maître de la Ligue", 1, 0);

    public final String label;
    public final int count;
    public final int typeCount;

    PostKind(String label, int count, int typeCount) {
        this.label = label;
        this.count = count;
        this.typeCount = typeCount;
    }

    public static PostKind of(String s) {
        try {
            return valueOf(s);
        } catch (Exception e) {
            return CHAMPION;
        }
    }
}
