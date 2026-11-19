import io.github.ravextech.rtg2powercontrol.BootModeController;
import io.github.ravextech.rtg2powercontrol.BootModeController.Mode;
import java.nio.file.*;
import java.util.*;

public final class BootModeControllerTest {
    static String original, quiet;
    static int passed;
    static final class Fake implements BootModeController.Environment, BootModeController.Backup {
        String value, saved; boolean dropWrite, failBackup; int writes; List<String> events=new ArrayList<>();
        Fake(String value){this.value=value;}
        public String read(String key){check(BootModeController.KEY.equals(key),"unexpected read key");events.add("read");return value;}
        public void write(String key,String value){check(BootModeController.KEY.equals(key),"unexpected write key");events.add("write");writes++;if(!dropWrite)this.value=value;}
        public void saveOriginal(String value)throws Exception{events.add("backup");if(failBackup)throw new Exception("backup failure");saved=value;}
    }
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    interface Throwing {void run()throws Exception;}
    static void rejects(Throwing action)throws Exception{boolean failed=false;try{action.run();}catch(Exception expected){failed=true;}check(failed,"expected rejection");}
    static BootModeController controller(Fake env){return new BootModeController(BootModeController.FINGERPRINT,original,quiet,env,env);}
    static void test(String name,Throwing action)throws Exception{action.run();passed++;System.out.println("PASS "+name);}
    public static void main(String[] args)throws Exception {
        original=new String(Files.readAllBytes(Paths.get(args[0],"switch_bootmode.original.txt")),"UTF-8");
        quiet=new String(Files.readAllBytes(Paths.get(args[0],"switch_bootmode.quiet.txt")),"UTF-8");
        test("quiet profile changes only the cold_boot branch",()->{
            String before="else if test ${reboot_mode} = cold_boot; then run try_auto_burn;";
            String after="else if test ${reboot_mode} = cold_boot; then setenv bootconfig ${bootconfig} androidboot.quiescent=1;setenv vout_init enable;run try_auto_burn;";
            check(original.indexOf(before)==original.lastIndexOf(before)&&original.contains(before),"branch not unique");
            check(original.replace(before,after).equals(quiet),"other branches changed");
        });
        test("read status never writes",()->{Fake f=new Fake(quiet);check(controller(f).readMode()==Mode.QUIET,"wrong mode");check(f.writes==0,"write on read");});
        test("save backup before applying and verify",()->{Fake f=new Fake(original);check(controller(f).changeMode(Mode.QUIET)==Mode.QUIET,"wrong result");check(f.value.equals(quiet),"wrong payload");check(f.saved.equals(original),"wrong backup");check(f.events.equals(Arrays.asList("read","backup","write","read")),"unsafe order");});
        test("restore exact original",()->{Fake f=new Fake(quiet);controller(f).changeMode(Mode.NORMAL);check(f.value.equals(original),"restore mismatch");});
        test("already selected mode is a no-op",()->{Fake f=new Fake(quiet);controller(f).changeMode(Mode.QUIET);check(f.writes==0&&f.saved==null,"unnecessary write");});
        test("different firmware blocks every write",()->{Fake f=new Fake(original);BootModeController c=new BootModeController("other/build",original,quiet,f,f);check(c.readMode()==Mode.UNSUPPORTED_FIRMWARE,"wrong status");rejects(()->c.changeMode(Mode.QUIET));check(f.writes==0,"unsupported write");});
        test("unknown script is preserved",()->{Fake f=new Fake(original+" ");check(controller(f).readMode()==Mode.UNKNOWN_SCRIPT,"wrong status");rejects(()->controller(f).changeMode(Mode.QUIET));check(f.writes==0,"unknown script overwritten");});
        test("failed backup blocks write",()->{Fake f=new Fake(original);f.failBackup=true;rejects(()->controller(f).changeMode(Mode.QUIET));check(f.writes==0,"write without backup");});
        test("failed readback is an error",()->{Fake f=new Fake(original);f.dropWrite=true;rejects(()->controller(f).changeMode(Mode.QUIET));check(f.writes==1,"wrong write count");});
        test("re-read when configuration changes after status",()->{Fake f=new Fake(original);BootModeController c=controller(f);check(c.readMode()==Mode.NORMAL,"wrong initial mode");f.value="changed externally";rejects(()->c.changeMode(Mode.QUIET));check(f.writes==0,"stale status used");});
        test("damaged packaged profile is refused",()->{Fake f=new Fake(original);rejects(()->new BootModeController(BootModeController.FINGERPRINT,original,quiet+"\n",f,f));});
        test("invalid mode cannot be written",()->{Fake f=new Fake(original);rejects(()->controller(f).changeMode(Mode.UNKNOWN_SCRIPT));check(f.writes==0,"invalid write");});
        System.out.println(passed+" checks passed");
    }
}
