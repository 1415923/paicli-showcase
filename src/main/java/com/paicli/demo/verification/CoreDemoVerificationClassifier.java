package com.paicli.demo.verification;

import com.paicli.demo.scenario.CoreDemoContractHash;
import com.paicli.runtime.VerificationResult;
import com.paicli.state.VerificationStore;
import org.w3c.dom.Element;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class CoreDemoVerificationClassifier {
    public CoreDemoVerificationEvidence classify(Path workspace, String phase,
            VerificationResult result, String sourceHash, String policyHash) throws Exception {
        Path reports = workspace.toRealPath(LinkOption.NOFOLLOW_LINKS)
                .resolve("target/surefire-reports");
        if (!Files.isDirectory(reports) || Files.isSymbolicLink(reports))
            throw new VerificationContractException("Surefire reports are missing");
        List<Path> xml;
        try (var stream=Files.list(reports)) {
            xml=stream.filter(Files::isRegularFile)
                    .filter(p->p.getFileName().toString().matches("TEST-.*\\.xml"))
                    .sorted().toList();
        }
        if (xml.isEmpty() || xml.size()>16)
            throw new VerificationContractException("Surefire report count is invalid");
        int tests=0, failures=0, errors=0, skipped=0;
        List<CoreDemoVerificationEvidence.FailedTest> failed=new ArrayList<>();
        String excerpt=null;
        for(Path report:xml) {
            if(Files.size(report)>524288 || Files.isSymbolicLink(report))
                throw new VerificationContractException("Surefire report exceeds bounds");
            Element suite=parse(report);
            tests+=integer(suite,"tests"); failures+=integer(suite,"failures");
            errors+=integer(suite,"errors"); skipped+=integer(suite,"skipped");
            var cases=suite.getElementsByTagName("testcase");
            for(int i=0;i<cases.getLength();i++) {
                Element test=(Element)cases.item(i);
                var fs=test.getElementsByTagName("failure");
                var es=test.getElementsByTagName("error");
                if(fs.getLength()+es.getLength()>0) {
                    failed.add(new CoreDemoVerificationEvidence.FailedTest(
                            required(test,"classname"),required(test,"name")));
                    if(excerpt==null) {
                        Element node=(Element)(fs.getLength()>0?fs.item(0):es.item(0));
                        excerpt=bound(node.getAttribute("message"),1024);
                    }
                }
            }
        }
        if(tests>100 || failed.size()!=failures+errors)
            throw new VerificationContractException("Surefire facts are inconsistent");
        String out=hash(result.outputExcerpt()), err=hash(result.errorExcerpt());
        String category=errors>0?"TEST_ERROR":failures>0?"ASSERTION_FAILURE":
                VerificationStore.STATUS_PASSED.equals(result.status())?"PASSED":"NON_TEST_FAILURE";
        List<CoreDemoVerificationEvidence.FailedTest> stable=failed.stream()
                .sorted(Comparator.comparing(CoreDemoVerificationEvidence.FailedTest::className)
                        .thenComparing(CoreDemoVerificationEvidence.FailedTest::methodName)).toList();
        String canonical=String.join("\n","phase="+phase,"commandPolicyHash="+policyHash,
                "sourceTreeHash="+sourceHash,"tests="+tests,"failures="+failures,
                "errors="+errors,"skipped="+skipped,"failedTests="+stable.stream()
                        .map(t->t.className()+"#"+t.methodName()).collect(java.util.stream.Collectors.joining(",")),
                "assertionCategory="+category,"stdoutHash="+out,"stderrHash="+err,
                "timedOut="+VerificationStore.STATUS_TIMED_OUT.equals(result.status()),
                "processCleanupComplete=true");
        return new CoreDemoVerificationEvidence(phase,result.verificationId(),policyHash,
                sourceHash,tests,failures,errors,skipped,stable,category,excerpt,out,err,
                VerificationStore.STATUS_TIMED_OUT.equals(result.status()),true,
                CoreDemoVerificationEvidence.hashCanonical(canonical));
    }

    private static Element parse(Path path)throws Exception{
        var f=DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        f.setExpandEntityReferences(false);
        Element root=f.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        if(!"testsuite".equals(root.getTagName()))
            throw new VerificationContractException("Unexpected Surefire root");
        return root;
    }
    private static int integer(Element e,String a)throws VerificationContractException{
        try{int v=Integer.parseInt(required(e,a));if(v<0)throw new NumberFormatException();return v;}
        catch(NumberFormatException x){throw new VerificationContractException("Invalid Surefire count");}
    }
    private static String required(Element e,String a)throws VerificationContractException{
        String v=e.getAttribute(a);if(v==null||v.isBlank()||v.length()>512)
            throw new VerificationContractException("Missing Surefire attribute: "+a);return v;
    }
    private static String hash(String value){
        return CoreDemoContractHash.sha256(Objects.toString(value,"").getBytes(StandardCharsets.UTF_8));
    }
    private static String bound(String v,int max){
        if(v==null)return null;String c=v.replace('\r',' ').replace('\n',' ').replace('\0',' ');
        return c.substring(0,Math.min(max,c.length()));
    }
    public static final class VerificationContractException extends Exception{
        public VerificationContractException(String message){super(message);}
    }
}