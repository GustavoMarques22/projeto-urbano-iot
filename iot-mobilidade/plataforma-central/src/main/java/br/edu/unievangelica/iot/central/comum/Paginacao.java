package br.edu.unievangelica.iot.central.comum;

public final class Paginacao {
    private Paginacao() {
    }

    public static int page(int p) {
        return Math.max(p, 0);
    }

    public static int size(int s) {
        if (s < 1) {
            return 20;
        }
        return Math.min(s, 200);
    }

    /** Aceita apenas asc ou desc, para uso seguro em ORDER BY. */
    public static String ordem(String o) {
        return "asc".equalsIgnoreCase(o) ? "ASC" : "DESC";
    }
}
