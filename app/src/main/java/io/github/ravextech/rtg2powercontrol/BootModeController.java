package io.github.ravextech.rtg2powercontrol;

/** The only component allowed to request a bootenv write. No Android dependencies. */
public final class BootModeController {
    public static final String FINGERPRINT = "RockTek/SEI804RT/YYJ:14/UKG3.250803.001/7475:user/release-keys";
    public static final String KEY = "ubootenv.var.switch_bootmode";
    public static final String ORIGINAL_SHA256 = "bafb86bfeb66f87cf7c3668eb330630828f7df04d04b70570809d6f996210ea3";
    public static final String QUIET_SHA256 = "bbed9cd68b82197f3ce71f32e7d4741c081f45b215a14f182ffbf87df3b0e293";
    public enum Mode { NORMAL, QUIET, UNSUPPORTED_FIRMWARE, UNKNOWN_SCRIPT }
    public interface Environment {
        String read(String key) throws Exception;
        void write(String key, String value) throws Exception;
    }
    public interface Backup { void saveOriginal(String original) throws Exception; }
    private final String fingerprint;
    private final String original;
    private final String quiet;
    private final Environment environment;
    private final Backup backup;

    public BootModeController(String fingerprint, String original, String quiet,
            Environment environment, Backup backup) {
        if (!ORIGINAL_SHA256.equals(sha256(original)) || !QUIET_SHA256.equals(sha256(quiet))) {
            throw new IllegalArgumentException("Boot script assets do not match the reviewed profile");
        }
        this.fingerprint = fingerprint;
        this.original = original;
        this.quiet = quiet;
        this.environment = environment;
        this.backup = backup;
    }

    public synchronized Mode readMode() throws Exception {
        if (!FINGERPRINT.equals(fingerprint)) return Mode.UNSUPPORTED_FIRMWARE;
        return classify(environment.read(KEY));
    }

    /** Re-read immediately before writing. Unknown states are never overwritten. */
    public synchronized Mode changeMode(Mode target) throws Exception {
        if (target != Mode.NORMAL && target != Mode.QUIET) {
            throw new IllegalArgumentException("Invalid target mode");
        }
        if (!FINGERPRINT.equals(fingerprint)) throw new IllegalStateException("Unsupported firmware");
        Mode before = classify(environment.read(KEY));
        if (before == Mode.UNKNOWN_SCRIPT) throw new IllegalStateException("Unrecognized boot script; nothing written");
        if (before == target) return target;
        backup.saveOriginal(original); // A failed backup prevents the write.
        String expected = target == Mode.QUIET ? quiet : original;
        environment.write(KEY, expected);
        if (!expected.equals(environment.read(KEY))) {
            throw new IllegalStateException("Bootenv readback mismatch. Do not disconnect power; inspect the device.");
        }
        return target;
    }

    private Mode classify(String value) {
        if (original.equals(value)) return Mode.NORMAL;
        if (quiet.equals(value)) return Mode.QUIET;
        return Mode.UNKNOWN_SCRIPT;
    }

    public static String sha256(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
