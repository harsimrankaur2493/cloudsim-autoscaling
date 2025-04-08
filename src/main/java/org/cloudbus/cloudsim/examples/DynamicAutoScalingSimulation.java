package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.text.DecimalFormat;
import java.util.*;

/**
 * A CloudSim example showing how to implement auto-scaling based on CPU usage.
 * Compatible with CloudSim 3.0.3
 */
public class DynamicAutoScalingSimulation {

    private static List<Cloudlet> cloudletList;
    private static List<Vm> vmList;
    private static int nextVmId = 0;
    private static int nextCloudletId = 0;
    
    // Auto-scaling thresholds
    private static final double SCALE_UP_THRESHOLD = 0.8; // 80% CPU utilization
    private static final double SCALE_DOWN_THRESHOLD = 0.3; // 30% CPU utilization
    private static final int MAX_VMS = 10;
    private static final int MIN_VMS = 1;
    
    // For testing and analysis
    private static int activeVmCount = 0;
    private static Map<Double, Integer> timeToVmCountMap = new HashMap<>();

    /**
     * Creates main() to run this example.
     *
     * @param args the args
     */
    public static void main(String[] args) {
        Log.printLine("Starting Dynamic Auto-Scaling Simulation...");

        try {
            // Initialize the CloudSim package
            int num_user = 1;
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false;
            CloudSim.init(num_user, calendar, trace_flag);

            // Create Datacenter
            Datacenter datacenter0 = createDatacenter("Datacenter_0");

            // Create Auto-scaling Broker (custom broker that implements auto-scaling)
            AutoScalingBroker broker = createAutoScalingBroker("AutoScalingBroker");
            int brokerId = broker.getId();

            // VM Parameters
            int mips = 1000;
            long size = 10000; // image size (MB)
            int ram = 512; // vm memory (MB)
            long bw = 1000;
            int pesNumber = 1; // number of CPUs
            String vmm = "Xen"; // VMM name

            // Create initial VM
            vmList = new ArrayList<>();
            Vm initialVm = createVm(brokerId, mips, pesNumber, ram, bw, size, vmm);
            vmList.add(initialVm);
            activeVmCount = 1;

            // Submit initial VM list to the broker
            broker.submitVmList(vmList);

            // Create initial cloudlet list
            cloudletList = new ArrayList<>();
            
            // Start with some initial load
            createAndSubmitCloudlets(broker, brokerId, 2, pesNumber);

            // Schedule load changes to test auto-scaling
            scheduleLoadChanges(broker);
            
            // Start the simulation
            CloudSim.startSimulation();

            // Print results
            List<Cloudlet> finishedCloudlets = broker.getCloudletReceivedList();
            printCloudletList(finishedCloudlets);
            
            // Print VM scaling information
            printScalingResults();
            
            CloudSim.stopSimulation();
            Log.printLine("Dynamic Auto-Scaling Simulation completed!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("The simulation has been terminated due to an unexpected error");
        }
    }

    /**
     * Creates a new VM with the next available ID
     */
    private static Vm createVm(int brokerId, int mips, int pesNumber, int ram, 
                               long bw, long size, String vmm) {
        Vm vm = new Vm(nextVmId++, brokerId, mips, pesNumber, ram, bw, size, vmm, 
                        new CloudletSchedulerTimeShared());
        return vm;
    }
    
    /**
     * Creates and submits new cloudlets to the broker
     */
    private static void createAndSubmitCloudlets(AutoScalingBroker broker, int brokerId, 
                                             int numberOfCloudlets, int pesNumber) {
        // Cloudlet parameters
        long length = 40000;
        long fileSize = 300;
        long outputSize = 300;
        UtilizationModel utilizationModel = new UtilizationModelFull();
        
        List<Cloudlet> newCloudlets = new ArrayList<>();
        
        for (int i = 0; i < numberOfCloudlets; i++) {
            Cloudlet cloudlet = new Cloudlet(nextCloudletId++, length, pesNumber, fileSize, 
                                      outputSize, utilizationModel, utilizationModel, utilizationModel);
            cloudlet.setUserId(brokerId);
            // Let the broker assign VMs
            newCloudlets.add(cloudlet);
        }
        
        broker.submitCloudletList(newCloudlets);
        cloudletList.addAll(newCloudlets);
    }
    
    /**
     * Schedules load changes throughout the simulation to test auto-scaling
     */
    private static void scheduleLoadChanges(final AutoScalingBroker broker) {
        // Define custom CloudSim tags for our events
        final int LOAD_CHANGE_TAG = 10000;
        final int VM_SCALING_CHECK_TAG = 10001;
        
        // Increase load at 100 seconds
        CloudSim.send(broker.getId(), broker.getId(), 100, LOAD_CHANGE_TAG, 5);
        
        // Increase load further at 200 seconds
        CloudSim.send(broker.getId(), broker.getId(), 200, LOAD_CHANGE_TAG, 8);
        
        // Decrease load at 300 seconds (some cloudlets will complete)
        CloudSim.send(broker.getId(), broker.getId(), 300, LOAD_CHANGE_TAG, 2);
        
        // Schedule auto-scaling checks every 20 seconds
        double time = 20;
        while (time < 400) {  // Run for 400 seconds simulation time
            CloudSim.send(broker.getId(), broker.getId(), time, VM_SCALING_CHECK_TAG, null);
            time += 20;
        }
        
        // Store these tags in the broker for use in event processing
        broker.setLoadChangeTag(LOAD_CHANGE_TAG);
        broker.setVmScalingCheckTag(VM_SCALING_CHECK_TAG);
    }

    /**
     * Custom Datacenter Broker that implements auto-scaling logic
     */
    static class AutoScalingBroker extends DatacenterBroker {
        
        private int LOAD_CHANGE_TAG;
        private int VM_SCALING_CHECK_TAG;
        
        public AutoScalingBroker(String name) throws Exception {
            super(name);
        }
        
        public void setLoadChangeTag(int tag) {
            this.LOAD_CHANGE_TAG = tag;
        }
        
        public void setVmScalingCheckTag(int tag) {
            this.VM_SCALING_CHECK_TAG = tag;
        }
        
        @Override
        protected void processOtherEvent(SimEvent ev) {
            if (ev.getTag() == VM_SCALING_CHECK_TAG) {
                processVmScaling();
            } else if (ev.getTag() == LOAD_CHANGE_TAG) {
                processLoadChange((int) ev.getData());
            } else {
                super.processOtherEvent(ev);
            }
        }
        
        private void processLoadChange(int numberOfNewCloudlets) {
            Log.printLine(CloudSim.clock() + ": Adding " + numberOfNewCloudlets + " new cloudlets");
            createAndSubmitCloudlets(this, getId(), numberOfNewCloudlets, 1);
        }
        
        private void processVmScaling() {
            // Calculate current utilization
            double totalUtilization = 0;
            int pendingCloudlets = 0;
            
            // Count running and pending cloudlets
            for (Cloudlet cloudlet : getCloudletSubmittedList()) {
                if (cloudlet.getCloudletStatus() == Cloudlet.INEXEC) {
                    totalUtilization += 1.0; // Each running cloudlet contributes 100% of one VM
                } else if (cloudlet.getCloudletStatus() == Cloudlet.CREATED ||
                           cloudlet.getCloudletStatus() == Cloudlet.QUEUED) {
                    pendingCloudlets++;
                }
            }
            
            // Calculate average utilization per VM
            double avgUtilization = activeVmCount > 0 ? totalUtilization / activeVmCount : 0;
            
            Log.printLine(CloudSim.clock() + ": Current utilization: " + avgUtilization + 
                         ", Active VMs: " + activeVmCount +
                         ", Pending cloudlets: " + pendingCloudlets);
            
            // Record VM count at this time for testing/verification
            timeToVmCountMap.put(CloudSim.clock(), activeVmCount);
            
            // Check if scaling is needed
            if (avgUtilization >= SCALE_UP_THRESHOLD && activeVmCount < MAX_VMS && pendingCloudlets > 0) {
                // Scale up - add a new VM
                scaleUp();
            } else if (avgUtilization <= SCALE_DOWN_THRESHOLD && activeVmCount > MIN_VMS) {
                // Scale down - remove a VM
                scaleDown();
            }
        }
        
        private void scaleUp() {
            Log.printLine(CloudSim.clock() + ": SCALING UP - Adding a new VM");
            
            // Create new VM with the same configuration as our initial VM
            Vm newVm = createVm(getId(), 1000, 1, 512, 1000, 10000, "Xen");
            List<Vm> newVmList = new ArrayList<>();
            newVmList.add(newVm);
            
            // Submit the new VM
            submitVmList(newVmList);
            DynamicAutoScalingSimulation.vmList.add(newVm); // ✅
            activeVmCount++;
        }
        
        private void scaleDown() {
            Log.printLine(CloudSim.clock() + ": SCALING DOWN - Removing a VM");
            
            // Find a VM with the least load
            // For simplicity, we'll just remove the last added VM
            // In a real scenario, you'd want to remove the least loaded VM
            if (!vmList.isEmpty()) {
                Vm vmToRemove = vmList.get(vmList.size() - 1);
                vmList.remove(vmToRemove);
                
                // In CloudSim 3.0.3, we need to manually handle VM destruction
                // by sending a VM_DESTROY event to the datacenter
//                int datacenterIds[] = getDatacenterIdsList();
                
                List<Integer> datacenterIdList = getDatacenterIdsList();  // Returns List<Integer>
                int[] datacenterIds = datacenterIdList.stream().mapToInt(Integer::intValue).toArray();

                if (datacenterIds.length > 0) {
                    int datacenterId = datacenterIds[0]; // Assuming only one datacenter for simplicity
                    
                    // Queue the VM destruction
                    send(datacenterId, CloudSim.getMinTimeBetweenEvents(), 
                         CloudSimTags.VM_DESTROY, vmToRemove);
                    
                    // Update VM count
                    activeVmCount--;
                }
            }
        }
    }

    private static AutoScalingBroker createAutoScalingBroker(String name) {
        AutoScalingBroker broker = null;
        try {
            broker = new AutoScalingBroker(name);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return broker;
    }
    
    /**
     * Prints scaling information for analysis
     */
    private static void printScalingResults() {
        Log.printLine("\n========== AUTO-SCALING RESULTS ==========");
        Log.printLine("Time (s)" + "\t" + "VM Count");
        
        // Sort times for chronological display
        List<Double> times = new ArrayList<>(timeToVmCountMap.keySet());
        Collections.sort(times);
        
        for (Double time : times) {
            Log.printLine(String.format("%.2f", time) + "\t\t" + timeToVmCountMap.get(time));
        }
    }

    /**
     * Creates the datacenter.
     *
     * @param name the name
     *
     * @return the datacenter
     */
    private static Datacenter createDatacenter(String name) {
        // Create a list to store our machine
        List<Host> hostList = new ArrayList<Host>();

        // Create List to store PEs or CPUs/Cores
        List<Pe> peList = new ArrayList<Pe>();

        int mips = 1000;

        // Create PEs and add to the list
        for (int i = 0; i < 8; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mips))); // 8 cores
        }

        // Create Host with its id and list of PEs
        int hostId = 0;
        int ram = 8192; // 8 GB RAM
        long storage = 1000000; // 1 TB storage
        int bw = 10000; // 10 Gbps

        hostList.add(
                new Host(
                        hostId,
                        new RamProvisionerSimple(ram),
                        new BwProvisionerSimple(bw),
                        storage,
                        peList,
                        new VmSchedulerTimeShared(peList)
                )
        );

        // Create a DatacenterCharacteristics object
        String arch = "x86"; 
        String os = "Linux"; 
        String vmm = "Xen";
        double time_zone = 10.0; 
        double cost = 3.0; 
        double costPerMem = 0.05; 
        double costPerStorage = 0.001; 
        double costPerBw = 0.0; 
        LinkedList<Storage> storageList = new LinkedList<Storage>();

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem, costPerStorage, costPerBw);

        // Create a PowerDatacenter object
        Datacenter datacenter = null;
        try {
            datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return datacenter;
    }

    /**
     * Prints the Cloudlet objects.
     *
     * @param list list of Cloudlets
     */
    private static void printCloudletList(List<Cloudlet> list) {
        int size = list.size();
        Cloudlet cloudlet;

        String indent = "    ";
        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        Log.printLine("Cloudlet ID" + indent + "STATUS" + indent +
                "Data center ID" + indent + "VM ID" + indent + "Time" + indent + "Start Time" + indent + "Finish Time");

        DecimalFormat dft = new DecimalFormat("###.##");
        for (int i = 0; i < size; i++) {
            cloudlet = list.get(i);
            Log.print(indent + cloudlet.getCloudletId() + indent + indent);

            if (cloudlet.getCloudletStatus() == Cloudlet.SUCCESS) {
                Log.print("SUCCESS");

                Log.printLine(indent + indent + cloudlet.getResourceId() + indent + indent + indent + cloudlet.getVmId() +
                        indent + indent + dft.format(cloudlet.getActualCPUTime()) + indent + indent + dft.format(cloudlet.getExecStartTime()) +
                        indent + indent + dft.format(cloudlet.getFinishTime()));
            }
        }
    }
}
