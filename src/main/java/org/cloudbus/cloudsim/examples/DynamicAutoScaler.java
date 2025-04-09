package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.*;

import java.util.*;

public class DynamicAutoScaler {

    private static final int INITIAL_VM_COUNT = 2;
    private static final int MAX_VM_COUNT = 5;
    private static final double CPU_UTIL_THRESHOLD = 0.7;
    private static final int MONITOR_INTERVAL = 10;

    private static List<Vm> vmList = new ArrayList<>();
    private static List<Cloudlet> cloudletList = new ArrayList<>();
    private static DatacenterBroker broker;

    public static void main(String[] args) {
        try {
            CloudSim.init(1, Calendar.getInstance(), false);

            Datacenter datacenter = createDatacenter("Datacenter_1");
            broker = new DatacenterBroker("Broker_1");

            int brokerId = broker.getId();
            createVMs(brokerId, INITIAL_VM_COUNT);
            createCloudlets(brokerId, 20);

            broker.submitVmList(vmList);
            broker.submitCloudletList(cloudletList);

            CloudSim.runClockTick();  // Start initial tick
            monitorAndScale();        // Simple loop for scaling

            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            printResults(broker.getCloudletReceivedList());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Datacenter createDatacenter(String name) throws Exception {
        List<Host> hostList = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            List<Pe> peList = Collections.singletonList(new Pe(0, new PeProvisionerSimple(1000)));
            Host host = new Host(i,
                    new RamProvisionerSimple(2048),
                    new BwProvisionerSimple(10000),
                    1_000_000,
                    peList,
                    new VmSchedulerTimeShared(peList));
            hostList.add(host);
        }

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.001, 0.0);

        return new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), new LinkedList<>(), 0.0);
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

    private static void monitorAndScale() {
        for (int time = 0; time <= 100; time += MONITOR_INTERVAL) {
            double avgUtil = estimateCpuUtilization();

            if (avgUtil > CPU_UTIL_THRESHOLD && vmList.size() < MAX_VM_COUNT) {
                int vmId = vmList.size();
                Vm newVm = new Vm(vmId, broker.getId(), 1000, 1, 1024, 1000, 10000,
                        "Xen", new CloudletSchedulerTimeShared());

                List<Vm> newVmList = new ArrayList<>();
                newVmList.add(newVm);

                broker.submitVmList(newVmList);
                vmList.add(newVm);

                System.out.printf("Auto-scaling: Added VM %d at time %d due to CPU util %.2f%n", vmId, time, avgUtil);
            }

            CloudSim.runClockTick();
        }
    }

    private static double estimateCpuUtilization() {
        return 0.6 + new Random().nextDouble() * 0.4; // Simulated 60% - 100%
    }

    private static void printResults(List<Cloudlet> cloudlets) {
        System.out.println("\n=== Cloudlet Execution Results ===");
        System.out.printf("%-12s%-10s%-15s%-10s%-10s%n", "Cloudlet ID", "Status", "Datacenter ID", "VM ID", "Time");

        for (Cloudlet c : cloudlets) {
            if (c.getStatus() == Cloudlet.SUCCESS) {
                System.out.printf("%-12d%-10s%-15d%-10d%-10.2f%n",
                        c.getCloudletId(), "SUCCESS", c.getResourceId(), c.getVmId(), c.getActualCPUTime());
            }
        }
    }
}
