package plugins.UPnP2;

import freenet.pluginmanager.*;
import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.support.igd.PortMappingListener;
import org.fourthline.cling.support.igd.callback.GetExternalIP;
import org.fourthline.cling.support.igd.callback.PortMappingAdd;
import org.fourthline.cling.support.model.PortMapping;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Created by xiaoyu on 12/22/15. 2
 */
public class UPnP2 implements FredPlugin, FredPluginThreadless, FredPluginIPDetector, FredPluginPortForward {

    private PluginRespirator pr;

    private UpnpService upnpService = new UpnpServiceImpl();

    private Set<DetectedIP> detectedIPs = new HashSet<>();
    private IGDRegistryListener registryListener;

    // ###################################
    // FredPlugin method(s)
    // ###################################

    @Override
    public void terminate() {
        System.out.println("Test plugin ended");
        // Release all resources and advertise BYEBYE to other UPnP devices
        upnpService.shutdown();
    }

    @Override
    public void runPlugin(PluginRespirator pr) {
        this.pr = pr;

        System.out.println("UPnP2 plugin started");

        // This will create necessary network resources for UPnP right away
        System.out.println("Starting Cling...");
        try {
            System.out.println(InetAddress.getLocalHost().toString());
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        try {
            Enumeration e = NetworkInterface.getNetworkInterfaces();
            while(e.hasMoreElements())
            {
                NetworkInterface n = (NetworkInterface) e.nextElement();
                Enumeration ee = n.getInetAddresses();
                while (ee.hasMoreElements())
                {
                    InetAddress i = (InetAddress) ee.nextElement();
                    System.out.println(i.getHostAddress());
                }
            }
        } catch (SocketException e1) {
            e1.printStackTrace();
        }


        // Add listeners for upnpService
        registryListener = new IGDRegistryListener();
        upnpService.getRegistry().addListener(registryListener);

        // Send a search message to all devices and services, they should respond soon
        upnpService.getControlPoint().search();

        // Let's wait 10 seconds for them to respond
        System.out.println("Waiting 10 seconds for the plugin to get enough devices...");
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    // ###################################
    // FredPluginIPDetector method(s)
    // ###################################

    @Override
    public DetectedIP[] getAddress() {
        System.out.println("Calling getAddress()");

        CountDownLatch latch = new CountDownLatch(1);
        registryListener.getExternalIP(latch);

        try {
            latch.await();
            return detectedIPs.toArray(new DetectedIP[detectedIPs.size()]);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void onChangePublicPorts(Set<ForwardPort> ports, ForwardPortCallback cb) {
        System.out.println("Calling onChangePublicPorts()");

        try {
            String localAddress = InetAddress.getLocalHost().toString();
            Set<PortMapping> portMappings = new HashSet<>();
            for (ForwardPort port : ports) {
                PortMapping.Protocol protocol;
                switch (port.protocol) {
                    case ForwardPort.PROTOCOL_UDP_IPV4:
                        protocol = PortMapping.Protocol.UDP;
                        break;
                    case ForwardPort.PROTOCOL_TCP_IPV4:
                        protocol = PortMapping.Protocol.TCP;
                        break;
                    default:
                        protocol = PortMapping.Protocol.UDP;
                }

                portMappings.add(
                        new PortMapping(
                                port.portNumber,
                                localAddress,
                                protocol,
                                "Freenet 0.7 " + port.name
                        )
                );
            }
            registryListener.addPortMappings(portMappings);

        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

    }

    // ###################################
    // Implementations
    // ###################################

    /**
     * Registry Listener for InternetGatewayDevice
     */
    private class IGDRegistryListener extends PortMappingListener {

        private Set<Service> connectionServices = new HashSet<>();

        public IGDRegistryListener() {
            super(new PortMapping[0]);
        }

        @Override
        synchronized public void deviceAdded(Registry registry, Device device) {

            Service connectionService;
            if ((connectionService = discoverConnectionService(device)) == null) return;

            connectionServices.add(connectionService);

        }

        @Override
        synchronized public void deviceRemoved(Registry registry, Device device) {
            super.deviceRemoved(registry, device);

            // Remove Services
            for (Service service : device.findServices()) {
                connectionServices.remove(service);
            }
        }

        synchronized public void addPortMappings(Set<PortMapping> newPortMappings) {

            this.portMappings = newPortMappings.toArray(new PortMapping[newPortMappings.size()]);

            // Unmap old ports
            beforeShutdown(upnpService.getRegistry());

            for (Service connectionService : connectionServices) {
                if (connectionService == null || portMappings.length == 0) return;

                System.out.println("Activating port mappings on: " + connectionService);

                final List<PortMapping> activeForService = new ArrayList<>();
                for (final PortMapping pm : portMappings) {
                    new PortMappingAdd(connectionService, upnpService.getControlPoint(), pm) {

                        @Override
                        public void success(ActionInvocation invocation) {
                            System.out.println("Port mapping added: " + pm);
                            activeForService.add(pm);
                        }

                        @Override
                        public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                            handleFailureMessage("Failed to add port mapping: " + pm);
                            handleFailureMessage("Reason: " + defaultMsg);
                        }
                    }.run(); // Synchronous!
                }

                activePortMappings.put(connectionService, activeForService);
            }

        }

        public void getExternalIP() {
            getExternalIP(new CountDownLatch(0));
        }

        public void getExternalIP(final CountDownLatch latch) {

            for (Service connectionService : connectionServices) {

                upnpService.getControlPoint().execute(
                        new GetExternalIP(connectionService) {

                            @Override
                            protected void success(String externalIPAddress) {
                                try {
                                    System.out.println("Get external IP: " + externalIPAddress);

                                    InetAddress inetAddress = InetAddress.getByName(externalIPAddress);

                                    detectedIPs.add(new DetectedIP(inetAddress, DetectedIP.NOT_SUPPORTED));

                                } catch (UnknownHostException e) {
                                    e.printStackTrace();
                                } finally {
                                    latch.countDown();
                                }
                            }

                            @Override
                            public void failure(ActionInvocation invocation,
                                                UpnpResponse operation,
                                                String defaultMsg) {
                                System.out.println("Unable to get external IP. Reason: " + defaultMsg);
                                latch.countDown();
                            }
                        }
                );
            }

        }

    }

}
