import io.github.ravextech.rtg2powercontrol.BootModeController;
import io.github.ravextech.rtg2powercontrol.BootModeController.Mode;
import io.github.ravextech.rtg2powercontrol.BootScript;
import java.nio.file.*;
import java.util.*;

public final class BootModeControllerTest {
    static String original,quiet;static int passed;
    static final class Fake implements BootModeController.Environment,BootModeController.Backup {
        String value,saved;boolean dropWrite,failBackup,failRead,readOnly,changeDuringBackup;int writes;List<String> events=new ArrayList<>();
        Fake(String value){this.value=value;}
        public String read(String key)throws Exception{check(BootModeController.KEY.equals(key),"unexpected key");events.add("read");if(failRead)throw new Exception("read denied");return value;}
        public void write(String key,String value)throws Exception{check(BootModeController.KEY.equals(key),"unexpected key");events.add("write");writes++;if(readOnly)throw new Exception("write denied");if(!dropWrite)this.value=value;}
        public void saveBeforeWrite(String value)throws Exception{events.add("backup");if(failBackup)throw new Exception("backup failure");saved=value;if(changeDuringBackup)this.value="externally changed";}
    }
    interface Throwing{void run()throws Exception;}
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static void rejects(Throwing action)throws Exception{boolean failed=false;try{action.run();}catch(Exception expected){failed=true;}check(failed,"expected rejection");}
    static BootModeController controller(Fake f){return new BootModeController(f,f);}
    static void test(String name,Throwing action)throws Exception{action.run();passed++;System.out.println("PASS "+name);}
    static void unknown(String script)throws Exception{Fake f=new Fake(script);check(controller(f).readMode()==Mode.UNKNOWN_SCRIPT,"wrong refusal status");rejects(()->controller(f).changeMode(Mode.QUIET));check(f.writes==0,"unexpected write");}
    static void roundtrip(String script)throws Exception{BootScript p=BootScript.inspect(script);check(!p.isQuiet,"unexpected mode");BootScript back=BootScript.inspect(p.quiet);check(back.isQuiet&&back.normal.equals(script),"not a byte-exact inverse");}
    public static void main(String[] args)throws Exception{
        original=new String(Files.readAllBytes(Paths.get(args[0],"switch_bootmode.original.txt")),"UTF-8");
        quiet=new String(Files.readAllBytes(Paths.get(args[0],"switch_bootmode.quiet.txt")),"UTF-8");
        test("legacy 1.0 profile recognized unchanged",()->{check(BootScript.inspect(original).quiet.equals(quiet),"legacy patch differs");check(BootScript.inspect(quiet).normal.equals(original),"legacy restore differs");});
        test("reading status has no write probe",()->{Fake f=new Fake(original);check(controller(f).readMode()==Mode.NORMAL,"wrong mode");check(f.events.equals(Arrays.asList("read")),"read has side effects");});
        test("actual snapshot before write; recheck and readback",()->{Fake f=new Fake(original);controller(f).changeMode(Mode.QUIET);check(f.saved.equals(original)&&f.value.equals(quiet),"wrong snapshot or target");check(f.events.equals(Arrays.asList("read","backup","read","write","read")),"unsafe order");});
        test("normal mode snapshots actual quiet value",()->{Fake f=new Fake(quiet);controller(f).changeMode(Mode.NORMAL);check(f.value.equals(original)&&f.saved.equals(quiet),"restore or snapshot mismatch");});
        test("same mode is no-op",()->{Fake f=new Fake(quiet);controller(f).changeMode(Mode.QUIET);check(f.writes==0&&f.saved==null,"unneeded write");});
        test("new firmware-specific recovery commands preserved",()->{String s=original.replace("run recovery_from_flash","run recovery_for_build_9999").replace("fastboot 0","fastboot 1");Fake f=new Fake(s);controller(f).changeMode(Mode.QUIET);check(f.value.contains("recovery_for_build_9999")&&f.value.contains("fastboot 1"),"other firmware overwritten");controller(f).changeMode(Mode.NORMAL);check(f.value.equals(s),"not exact restore");});
        test("spaces, newlines and comments preserved",()->{roundtrip("# build-specific dispatcher\n"+original.replace(";",";\n").replace("then run try_auto_burn","then\n    run try_auto_burn")+"\n# end\n");});
        test("elif dispatcher supported",()->{roundtrip("get_rebootmode;if test ${reboot_mode} = quiescent; then setenv bootconfig ${bootconfig} androidboot.quiescent=1;setenv vout_init enable;run try_auto_burn;elif test ${reboot_mode} = cold_boot; then run try_auto_burn;else run fallback;fi;");});
        test("quoted lookalike branches are not patched",()->{roundtrip(original.replace("run recovery_from_flash","echo \"if test ${reboot_mode} = cold_boot; then run try_auto_burn; fi;\";run recovery_from_flash"));});
        test("missing environment refused",()->unknown(null));
        test("absent variable marker refused",()->unknown("__ABSENT__"));
        test("missing native quiescent refused",()->unknown(original.replace(" = quiescent;"," = not_quiescent;")));
        test("missing cold_boot refused",()->unknown(original.replace(" = cold_boot;"," = another_reason;")));
        test("duplicate cold_boot refused",()->unknown(original.replace(" = fastboot;"," = cold_boot;")));
        test("duplicate quiescent refused",()->unknown(original.replace(" = fastboot;"," = quiescent;")));
        test("different quiet mechanism refused",()->unknown(original.replace("setenv bootconfig ${bootconfig}","setenv bootargs ${bootargs}")));
        test("quiescent flag zero refused",()->unknown(original.replace("androidboot.quiescent=1","androidboot.quiescent=0")));
        test("extra cold-branch command refused",()->unknown(original.replace("= cold_boot; then run", "= cold_boot; then erase env;run")));
        test("nested cold-branch logic refused",()->unknown(original.replace("= cold_boot; then run try_auto_burn;", "= cold_boot; then if test a = b; then run try_auto_burn;fi;")));
        test("unrelated dispatch condition refused",()->unknown(original.replace("test ${reboot_mode} = factory_reset","test ${board} = special")));
        test("mode branches hidden in a different reason's THEN refused",()->unknown("get_rebootmode;if test ${reboot_mode} = factory_reset; then if test ${reboot_mode} = quiescent; then setenv bootconfig ${bootconfig} androidboot.quiescent=1;setenv vout_init enable;run try_auto_burn;elif test ${reboot_mode} = cold_boot; then run try_auto_burn;fi;else run fallback;fi;"));
        test("missing closing fi refused",()->unknown(original.substring(0,original.length()-3)));
        test("extra closing fi refused",()->unknown(original+"fi;"));
        test("top-level suffix refused",()->unknown(original+"setenv bootconfig bad;"));
        test("missing get_rebootmode refused",()->unknown(original.replace("get_rebootmode;","")));
        test("multiple get_rebootmode refused",()->unknown(original.replace("fastboot 0","get_rebootmode;fastboot 0")));
        test("operator outside quotes refused",()->unknown(original.replace("fastboot 0","fastboot 0 || reset")));
        test("unterminated quote refused",()->unknown(original+"echo \"broken"));
        test("foreign quiet prefix formatting refused",()->unknown(quiet.replace("setenv vout_init enable;run try_auto_burn;else if test ${reboot_mode} = fastboot", "setenv vout_init enable; run try_auto_burn;else if test ${reboot_mode} = fastboot")));
        test("possibly truncated API input refused",()->unknown(original+new String(new char[4000]).replace('\0',' ')));
        test("patch exceeding API limit refused",()->{String s=original.replaceFirst("run recovery_from_flash","echo "+new String(new char[3350]).replace('\0','x')+";run recovery_from_flash");check(s.getBytes("UTF-8").length<4000,"fixture should fit before patching");unknown(s);});
        test("failed backup blocks write",()->{Fake f=new Fake(original);f.failBackup=true;rejects(()->controller(f).changeMode(Mode.QUIET));check(f.writes==0,"write without backup");});
        test("read denied never writes",()->{Fake f=new Fake(original);f.failRead=true;rejects(()->controller(f).readMode());rejects(()->controller(f).changeMode(Mode.QUIET));check(f.writes==0,"unexpected write");});
        test("write denied is not success",()->{Fake f=new Fake(original);f.readOnly=true;rejects(()->controller(f).changeMode(Mode.QUIET));check(f.value.equals(original),"read-only value changed");});
        test("failed readback is not success",()->{Fake f=new Fake(original);f.dropWrite=true;rejects(()->controller(f).changeMode(Mode.QUIET));});
        test("stale UI status cannot authorize unknown script",()->{Fake f=new Fake(original);BootModeController c=controller(f);c.readMode();f.value="changed";rejects(()->c.changeMode(Mode.QUIET));check(f.writes==0,"stale state write");});
        test("configuration change during backup refused",()->{Fake f=new Fake(original);f.changeDuringBackup=true;rejects(()->controller(f).changeMode(Mode.QUIET));check(f.writes==0,"concurrent state overwritten");});
        test("invalid target refused",()->{Fake f=new Fake(original);rejects(()->controller(f).changeMode(Mode.UNKNOWN_SCRIPT));check(f.writes==0,"invalid write");});
        test("firmware variations round-trip without importing a factory template",()->{for(int i=0;i<100;i++)roundtrip(original.replace("run recovery_from_flash","echo build_"+i+";run custom_recovery_"+i).replace("fastboot 0","fastboot "+(i%4)));});
        System.out.println(passed+" checks passed");
    }
}
