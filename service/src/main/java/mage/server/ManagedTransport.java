package mage.server;

import mage.interfaces.MageServer;
import mage.remote.Connection;
import org.jboss.remoting.InvokerLocator;

/** Verified package-private seam; managed guards are installed before admissions. */
final class ManagedTransport {
    static void start(MainManagerFactory managers) throws Exception {
        var connection = new Connection();
        connection.setHost(managers.configSettings().getServerAddress());
        connection.setPort(managers.configSettings().getPort());
        var server = new Main.MageTransporterServer(managers, new InvokerLocator(connection.getURI()),
                new MageServerImpl(managers, java.util.UUID.randomUUID().toString(), false, false),
                MageServer.class.getName(), new Main.MageServerInvocationHandler(managers));
        server.start();
    }
}
