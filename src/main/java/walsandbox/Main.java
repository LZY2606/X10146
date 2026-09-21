package walsandbox;

import walsandbox.sandbox.SandboxStore;
import walsandbox.web.WebServer;

import java.nio.file.Path;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5226;
        Path dataDir = Path.of("sandbox-data");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> dataDir = Path.of(args[++i]);
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    System.err.println("用法: [--host 127.0.0.1] [--port 5226] "
                            + "[--data sandbox-data]");
                    System.exit(2);
                }
            }
        }
        SandboxStore store = new SandboxStore(dataDir);
        WebServer web = new WebServer(store);
        web.start(host, port);
        System.out.println("WAL 恢复沙盒已启动: http://" + host + ":" + web.port());
        System.out.println("数据目录: " + dataDir.toAbsolutePath());
    }
}
