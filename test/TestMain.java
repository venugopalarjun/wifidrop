import io.wifidrop.*;
import java.io.File;
public class TestMain {
    public static void main(String[] a) throws Exception {
        new DropServer(new FileStorage(new File(a[0])), a[1], new Stats(), Integer.parseInt(a[2])).start();
        Thread.sleep(Long.MAX_VALUE);
    }
}
