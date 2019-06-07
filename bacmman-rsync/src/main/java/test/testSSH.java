package test;


import com.github.fracpete.processoutput4j.core.StreamingProcessOutputType;
import com.github.fracpete.processoutput4j.core.StreamingProcessOwner;
import com.github.fracpete.processoutput4j.output.ConsoleOutputProcessOutput;
import com.github.fracpete.processoutput4j.output.StreamingProcessOutput;
import com.github.fracpete.rsync4j.RSync;
import com.github.fracpete.rsync4j.Ssh;
import com.github.fracpete.rsync4j.core.Binaries;
import org.apache.commons.lang.SystemUtils;

public class testSSH {

    public static void main(String[] args) {

        rsync("LJP:/data/Images/TestRSync", "/data/");
        rsync("/data/TestRSync2", "LJP:/data/Images/");
        ls();
    }
    public static void rsync(String source, String dest) {
        // interface: local & remote root dir + remote hostname -> list dirs + choose who is source. Mkdir remote also! + id : + generate dsa key & add to remote ?
        // binaries location (set defaults depending on OS)
        RSync rsync = new RSync()
                .destination("/data/")
                .source("LJP:/data/Images/TestRSync")
                .recursive(true)
                .verbose(true)
                .archive(true)
                .delete(true)

                .compress(true);

        if (SystemUtils.IS_OS_WINDOWS) {
            try {
                rsync.rsh(Binaries.sshBinary() + " -i C:\\keys\\rsa.ppk");
            } catch(Exception e) {
                System.out.println(e);
            }
        } else {
            rsync.rsh("ssh -i /home/jollion/.ssh/id_rsa");
        }


        StreamingProcessOutput output = new StreamingProcessOutput(new Output());
        try {
            output.monitor(rsync.builder());
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    public static void ls() {
        Ssh ssh = new Ssh()
                .outputCommandline(true)
                .verbose(0)

                .hostname("ljp")
                .command("ls -d /data/*/");
        ConsoleOutputProcessOutput output = new ConsoleOutputProcessOutput();

        try {
            output.monitor(ssh.builder());
        } catch (Exception e) {
            System.out.println(e);
        }
    }
    public static class Output implements StreamingProcessOwner {
        public StreamingProcessOutputType getOutputType() {
            return StreamingProcessOutputType.BOTH;
        }
        public void processOutput(String line, boolean stdout) {
            System.out.println((stdout ? "[OUT] " : "[ERR] ") + line);
        }
    }
}
