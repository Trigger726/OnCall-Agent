import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
public class VerifyCiYaml {
    public static void main(String[] args) throws Exception {
        var options=new LoaderOptions(); options.setAllowDuplicateKeys(false);
        Map<?,?> document=new Yaml(new SafeConstructor(options)).load(Files.readString(Path.of(".github/workflows/ci.yml")));
        Map<?,?> jobs=(Map<?,?>)document.get("jobs");
        if(jobs.size()!=21 || !jobs.containsKey("oncall-open-handoff-upgrade-mysql-integration")) throw new AssertionError("Require original twenty plus upgrade job");
        Map<?,?> container=(Map<?,?>)jobs.get("container-smoke");
        if(container==null) container=(Map<?,?>)jobs.get("container");
        System.out.println("{\"parsedYamlJobs\":"+jobs.size()+",\"duplicatesRejected\":true}");
    }
}
