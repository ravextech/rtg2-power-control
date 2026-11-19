package io.github.ravextech.rtg2powercontrol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A small parser for a reboot-mode dispatcher, not a shell interpreter. */
public final class BootScript {
    private static final String PREFIX="setenv bootconfig ${bootconfig} androidboot.quiescent=1;setenv vout_init enable;";
    public final String normal,quiet;
    public final boolean isQuiet;
    private BootScript(String normal,String quiet,boolean isQuiet){this.normal=normal;this.quiet=quiet;this.isQuiet=isQuiet;}

    public static BootScript inspect(String source){
        if(source==null)throw new IllegalArgumentException("Missing switch_bootmode");
        checkSize(source);
        Parser p=new Parser(source);List<Statement> root=p.sequence(0,true);
        if(p.position!=p.tokens.size()||root.size()!=2||!command(root.get(0),"get_rebootmode")||!(root.get(1) instanceof Conditional)||p.rebootReads!=1)
            throw new IllegalArgumentException("Not a standalone reboot-mode dispatcher");
        Branch cold=null,q=null;
        for(Branch b:p.branches){
            if(condition(b.condition,"cold_boot")){if(cold!=null||!b.safe)throw new IllegalArgumentException("Ambiguous cold_boot branch");cold=b;}
            if(condition(b.condition,"quiescent")){if(q!=null||!b.safe)throw new IllegalArgumentException("Ambiguous quiescent branch");q=b;}
        }
        if(cold==null||q==null||!quietBody(q.body))throw new IllegalArgumentException("A recognized native quiescent branch is required");
        if(cold.body.size()==1&&command(cold.body.get(0),"run","try_auto_burn")){
            int offset=cold.body.get(0).start;
            String result=source.substring(0,offset)+PREFIX+source.substring(offset);checkSize(result);
            return new BootScript(source,result,false);
        }
        if(quietBody(cold.body)){
            int begin=cold.body.get(0).start,end=cold.body.get(2).start;
            // Remove only our exact prefix: an inverse must preserve the original bytes.
            if(!PREFIX.equals(source.substring(begin,end)))throw new IllegalArgumentException("Quiet cold_boot prefix is not a recognized reversible patch");
            return new BootScript(source.substring(0,begin)+source.substring(end),source,true);
        }
        throw new IllegalArgumentException("Unexpected commands in cold_boot");
    }
    private static void checkSize(String s){
        try{if(s.getBytes("UTF-8").length>=4000)throw new IllegalArgumentException("Boot script exceeds safe vendor-API length");}
        catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
    private static boolean quietBody(List<Statement> body){return body.size()==3
        &&command(body.get(0),"setenv","bootconfig","${bootconfig}","androidboot.quiescent=1")
        &&command(body.get(1),"setenv","vout_init","enable")&&command(body.get(2),"run","try_auto_burn");}
    private static boolean command(Statement s,String...w){return s instanceof Simple&&((Simple)s).words.equals(Arrays.asList(w));}
    private static boolean condition(List<String> w,String value){return w.equals(Arrays.asList("test","${reboot_mode}","=",value));}
    private static boolean modeCondition(List<String> w){return w.size()==4&&w.get(0).equals("test")&&w.get(1).equals("${reboot_mode}")&&w.get(2).equals("=")&&w.get(3).matches("[A-Za-z0-9_]+");}
    private static final class Token {final String text;final int start;Token(String t,int s){text=t;start=s;}}
    private abstract static class Statement {final int start;Statement(int s){start=s;}}
    private static final class Simple extends Statement {final List<String> words;Simple(int s,List<String>w){super(s);words=w;}}
    private static final class Conditional extends Statement {Conditional(int s){super(s);}}
    private static final class Branch {
        final List<String> condition;final List<Statement> body;final boolean safe;
        Branch(List<String>c,List<Statement>b,boolean s){condition=c;body=b;safe=s;}
    }
    private static List<Token> lex(String s){
        List<Token> tokens=new ArrayList<>();int i=0;
        while(i<s.length()){
            char c=s.charAt(i);
            if(c=='\n'||c==';'){tokens.add(new Token(";",i++));continue;}
            if(Character.isWhitespace(c)){i++;continue;}
            if(c=='#'){while(i<s.length()&&s.charAt(i)!='\n')i++;continue;}
            int start=i;
            while(i<s.length()){
                c=s.charAt(i);if(Character.isWhitespace(c)||c==';')break;
                if(c=='`'||"&|()<>".indexOf(c)>=0||s.startsWith("$(",i))throw new IllegalArgumentException("Unsupported shell operator");
                if(c=='\\'){if(++i>=s.length())throw new IllegalArgumentException("Unfinished escape");i++;continue;}
                if(c=='\''||c=='\"'){
                    char quote=c;i++;boolean closed=false;
                    while(i<s.length()){
                        char q=s.charAt(i++);if(q==quote){closed=true;break;}
                        if(q=='\\'&&quote=='\"'){if(i>=s.length())throw new IllegalArgumentException("Unfinished quoted escape");i++;}
                    }
                    if(!closed)throw new IllegalArgumentException("Unterminated quote");continue;
                }
                i++;
            }
            tokens.add(new Token(s.substring(start,i),start));
            if(tokens.size()>2048)throw new IllegalArgumentException("Too many script tokens");
        }
        return tokens;
    }
    private static final class Parser {
        final List<Token> tokens;final List<Branch> branches=new ArrayList<>();int position,rebootReads;
        Parser(String s){tokens=lex(s);}
        boolean at(String s){return position<tokens.size()&&tokens.get(position).text.equals(s);}
        void expect(String s){if(!at(s))throw new IllegalArgumentException("Expected "+s);position++;}
        void separators(){while(at(";"))position++;}
        List<Statement> sequence(int depth,boolean safe){
            if(depth>32)throw new IllegalArgumentException("Nested script is too deep");
            List<Statement> list=new ArrayList<>();separators();
            while(position<tokens.size()&&!at("else")&&!at("elif")&&!at("fi")){
                if(at("if"))list.add(conditional(depth,safe));
                else{
                    int start=tokens.get(position).start;List<String> words=new ArrayList<>();
                    while(position<tokens.size()&&!at(";")){
                        String w=tokens.get(position++).text;
                        if(Arrays.asList("if","then","else","elif","fi","for","while","until","case","select","do","done","esac","{","}").contains(w))throw new IllegalArgumentException("Unsupported or misplaced shell control word");
                        words.add(w);
                    }
                    if(words.isEmpty())throw new IllegalArgumentException("Empty command");
                    if(words.get(0).equals("get_rebootmode"))rebootReads++;
                    list.add(new Simple(start,words));
                }
                separators();
            }
            return list;
        }
        Conditional conditional(int depth,boolean safe){
            int start=tokens.get(position).start;expect("if");boolean chainSafe=safe;
            while(true){
                List<String> condition=new ArrayList<>();while(position<tokens.size()&&!at(";"))condition.add(tokens.get(position++).text);
                if(condition.isEmpty())throw new IllegalArgumentException("Empty condition");
                expect(";");separators();expect("then");chainSafe=chainSafe&&modeCondition(condition);
                // A target inside another reason's THEN is not a reachable dispatcher arm.
                // Only ELSE/ELIF chains may inherit the reboot-dispatch context.
                List<Statement> body=sequence(depth+1,false);branches.add(new Branch(condition,body,chainSafe));
                if(at("elif")){position++;continue;}
                if(at("else")){position++;sequence(depth+1,chainSafe);}
                expect("fi");return new Conditional(start);
            }
        }
    }
}
