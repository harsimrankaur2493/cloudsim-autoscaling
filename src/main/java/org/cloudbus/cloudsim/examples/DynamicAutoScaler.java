package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.util.*;

public class DynamicAutoScaler {

    private static final int INITIAL_VM_COUNT = 2;
    private static final int MAX_VM_COUNT = 10;
    private static final double CPU_UTIL_THRESHOLD = 0.7;
    private static final int MONITOR_INTERVAL = 10;

    private static List<Vm> vmList = new ArrayList<>();
    private static List<Cloudlet> cloudletList = new ArrayList<>();
    private static DatacenterBroker broker;

    public static void main(String[] args) {
        try {
            // Initialize CloudSim
            int numUsers = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceFlag = false;
            CloudSim.init(numUsers, calendar, traceFlag);

            Datacenter datacenter = createDatacenter("Datacenter_1");
            broker = createBroker();
            int brokerId = broker.getId();

            createVMs(brokerId, INITIAL_VM_COUNT);
            createCloudlets(brokerId, 20);

            broker.submitVmList(vmList);
            broker.submitCloudletList(cloudletList);

            // Schedule dynamic scaling before simulation starts
            CloudSim.runClockTick(); // Advance initial tick before monitoring
            monitorAndScale(datacenter); // Scaling logic

            // Start and stop simulation
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            List<Cloudlet> finishedCloudlets = broker.getCloudletReceivedList();
            printCloudletResults(finishedCloudlets);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Datacenter createDatacenter(String name) throws Exception {
        List<Host> hostList = new ArrayList<>();
        int mips = 1000, ram = 2048, bw = 10000;
        long storage = 1_000_000;

        for (int i = 0; i < 2; i++) {
            List<Pe> peList = Collections.singletonList(new Pe(0, new PeProvisionerSimple(mips)));
            Host host = new Host(i,
                    new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw),
                    storage,
                    peList,
                    new VmSchedulerTimeShared(peList));
            hostList.add(host);
        }

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.001, 0.0);

        return new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), new LinkedList<>(), 0.0);
    }

    private static DatacenterBroker createBroker() throws Exception {
        return new DatacenterBroker("Broker");
    }

    private static void createVMs(int brokerId, int count) {
        for (int i = 0; i < count; i++) {
            Vm vm = new Vm(i, brokerId, 1000, 1, 1024, 1000, 10000,
                    "Xen", new CloudletSchedulerTimeShared());
            vmList.add(vm);
        }
    }

    private static void createCloudlets(int brokerId, int count) {
        UtilizationModel model = new UtilizationModelFull();
        for (int i = 0; i < count; i++) {
            Cloudlet cloudlet = new Cloudlet(i, 40000, 1, 300, 300, model, model, model);
            cloudlet.setUserId(brokerId);
            cloudletList.add(cloudlet);
        }
    }

    private static void monitorAndScale(Datacenter datacenter) {
        for (int time = 0; time <= 100; time += MONITOR_INTERVAL) {
            double avgUtil = estimateAverageCpuUtil();

            if (avgUtil > CPU_UTIL_THRESHOLD && vmList.size() < MAX_VM_COUNT) {
                int newVmId = vmList.size();
                Vm vm = new Vm(newVmId, broker.getId(), 1000, 1, 1024, 1000, 10000,
                        "Xen", new CloudletSchedulerTimeShared());

                List<Vm> newVmList = new ArrayList<>();
                newVmList.add(vm);
                broker.submitVmList(newVmList);  // Must re-submit updated VM list
                vmList.add(vm);

                System.out.printf("Added new VM at time %d due to high CPU utilization: %.2f%n", time, avgUtil);
            }

            CloudSim.runClockTick();  // advance simulation step
        }
    }

    private static double estimateAverageCpuUtil() {
        Random rand = new Random();
        return 0.6 + rand.nextDouble() * 0.4; // Simulate between 60%-100%
    }

    private static void printCloudletResults(List<Cloudlet> list) {
        String indent = "    ";
        System.out.println("========== CLOUDLET OUTPUT ==========");
        System.out.println("Cloudlet ID" + indent + "STATUS" + indent + "Datacenter ID" +
                indent + "VM ID" + indent + "Execution Time");

        for (Cloudlet cloudlet : list) {
            if (cloudlet.getStatus() == Cloudlet.SUCCESS) {
                System.out.printf("%d%sSUCCESS%s%d%s%d%s%.2f%n",
                        cloudlet.getCloudletId(), indent,
                        indent, cloudlet.getResourceId(),
                        indent, cloudlet.getVmId(),
                        indent, cloudlet.getActualCPUTime());
            }
        }
    }
}
