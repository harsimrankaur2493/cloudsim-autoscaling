package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
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
            // Step 1: Initialize CloudSim
            int numUsers = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceFlag = false;
            CloudSim.init(numUsers, calendar, traceFlag);

            // Step 2: Create Datacenter and Broker
            Datacenter datacenter = createDatacenter("Datacenter_1");
            broker = createBroker();
            int brokerId = broker.getId();

            // Step 3: Create Initial VMs and Cloudlets
            createVMs(brokerId, INITIAL_VM_COUNT);
            createCloudlets(brokerId, 20);

            // Step 4: Submit lists to broker
            broker.submitVmList(vmList);
            broker.submitCloudletList(cloudletList);

            // Step 5: Add AutoScaler as an entity
            AutoScaler autoScaler = new AutoScaler("AutoScaler", MONITOR_INTERVAL, vmList, broker,
                    CPU_UTIL_THRESHOLD, MAX_VM_COUNT);
            CloudSim.addEntity(autoScaler);

            // Step 6: Start Simulation
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            // Step 7: Print Results
            printCloudletResults(broker.getCloudletReceivedList());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ================= Helper Methods =================

    private static Datacenter createDatacenter(String name) throws Exception {
        List<Host> hostList = new ArrayList<>();
        int mips = 1000;
        int ram = 2048;
        long storage = 1000000;
        int bw = 10000;

        for (int i = 0; i < 2; i++) {
            List<Pe> peList = new ArrayList<>();
            peList.add(new Pe(0, new PeProvisionerSimple(mips)));

            hostList.add(new Host(i, new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw), storage, peList,
                    new VmSchedulerTimeShared(peList)));
        }

        String arch = "x86";
        String os = "Linux";
        String vmm = "Xen";
        double time_zone = 10.0;
        double cost = 3.0;
        double costPerMem = 0.05;
        double costPerStorage = 0.001;
        double costPerBw = 0.0;

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem, costPerStorage, costPerBw);

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
        UtilizationModel utilizationModel = new UtilizationModelFull();
        for (int i = 0; i < count; i++) {
            Cloudlet cloudlet = new Cloudlet(i, 40000, 1, 300, 300,
                    utilizationModel, utilizationModel, utilizationModel);
            cloudlet.setUserId(brokerId);
            cloudletList.add(cloudlet);
        }
    }

    private static void printCloudletResults(List<Cloudlet> list) {
        String indent = "    ";
        System.out.println("========== OUTPUT ==========");
        System.out.println("Cloudlet ID" + indent + "STATUS" + indent +
                "Data center ID" + indent + "VM ID" + indent + "Time");

        for (Cloudlet cloudlet : list) {
            System.out.print(cloudlet.getCloudletId() + indent + indent);
            if (cloudlet.getStatus() == Cloudlet.SUCCESS) {
                System.out.println("SUCCESS" + indent + indent +
                        cloudlet.getResourceId() + indent + indent + cloudlet.getVmId() +
                        indent + cloudlet.getActualCPUTime());
            }
        }
    }
}

// ================= AutoScaler Class =================

class AutoScaler extends SimEntity {

    private final int interval;
    private final List<Vm> vmList;
    private final DatacenterBroker broker;
    private final double cpuThreshold;
    private final int maxVmCount;

    public AutoScaler(String name, int interval, List<Vm> vmList, DatacenterBroker broker,
                      double cpuThreshold, int maxVmCount) {
        super(name);
        this.interval = interval;
        this.vmList = vmList;
        this.broker = broker;
        this.cpuThreshold = cpuThreshold;
        this.maxVmCount = maxVmCount;
    }

    @Override
    public void startEntity() {
        schedule(getId(), interval, 1);
    }

    @Override
    public void processEvent(SimEvent ev) {
        double util = estimateAverageCpuUtil();

        if (util > cpuThreshold && vmList.size() < maxVmCount) {
            int vmId = vmList.size();
            Vm vm = new Vm(vmId, broker.getId(), 1000, 1, 1024, 1000, 10000,
                    "Xen", new CloudletSchedulerTimeShared());
            broker.submitVmList(Collections.singletonList(vm));
            vmList.add(vm);

            System.out.println(CloudSim.clock() + ": AutoScaler - Added VM #" + vmId + " due to CPU util = " + util);
        }

        schedule(getId(), interval, 1); // Schedule next check
    }

    @Override
    public void shutdownEntity() {
        System.out.println("AutoScaler: shutting down at time " + CloudSim.clock());
    }

    private double estimateAverageCpuUtil() {
        Random r = new Random();
        return 0.6 + r.nextDouble() * 0.4;  // Simulate 60–100% CPU usage
    }
}
