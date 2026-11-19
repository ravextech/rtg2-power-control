package io.github.ravextech.rtg2powercontrol;

/** The only component allowed to request a bootenv write. No firmware-number allowlist. */
public final class BootModeController {
    public static final String TESTED_FIRMWARE="RockTek/SEI804RT/YYJ:14/UKG3.250803.001/7475:user/release-keys";
    public static final String KEY="ubootenv.var.switch_bootmode";
    public enum Mode { NORMAL, QUIET, UNKNOWN_SCRIPT }
    public interface Environment {
        String read(String key)throws Exception;
        void write(String key,String value)throws Exception;
    }
    public interface Backup {void saveBeforeWrite(String current)throws Exception;}
    private final Environment environment;
    private final Backup backup;
    public BootModeController(Environment environment,Backup backup){this.environment=environment;this.backup=backup;}
    public synchronized Mode readMode()throws Exception{
        String value=environment.read(KEY);
        try{return BootScript.inspect(value).isQuiet?Mode.QUIET:Mode.NORMAL;}
        catch(IllegalArgumentException unsupported){return Mode.UNKNOWN_SCRIPT;}
    }
    public synchronized Mode changeMode(Mode target)throws Exception{
        if(target!=Mode.NORMAL&&target!=Mode.QUIET)throw new IllegalArgumentException("Invalid target mode");
        String before=environment.read(KEY);
        BootScript profile=BootScript.inspect(before);
        String expected=target==Mode.QUIET?profile.quiet:profile.normal;
        if(expected.equals(before))return target;
        backup.saveBeforeWrite(before);
        if(!before.equals(environment.read(KEY)))throw new IllegalStateException("Boot script changed during backup; nothing written");
        environment.write(KEY,expected);
        if(!expected.equals(environment.read(KEY)))throw new IllegalStateException("Bootenv readback mismatch. Do not disconnect power; inspect the device.");
        return target;
    }
    public static String sha256(String value){
        try{
            byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
