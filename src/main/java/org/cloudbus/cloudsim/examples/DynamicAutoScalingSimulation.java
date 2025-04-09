package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.provisioners.*;

import java.text.DecimalFormat;
import java.util.*;

/**
 * Enhanced Dynamic Auto-Scaling Simulation for CloudSim 3.0.3
 * Demonstrates automatic VM scaling based on CPU utilization and workload
 */
public class DynamicAutoScalingSimulation {

    // Lists to track resources
    private static List<Cloudlet> cloudletList;
    private static List<Vm> vmList;
    
    // Simulation configuration
    private static final int SIMULATION_DURATION = 1000; // seconds
    private static final int INITIAL_CLOUDLETS = 2;
    private static final int CLOUDLETS_AT_100S = 5;
    private static final int CLOUDLETS_AT_200S = 8;
    private static final int CLOUDLETS_AT_300S = 2;
    
    // Auto-scaling thresholds
    private static final double SCALE_UP_THRESHOLD = 0.7; // 70% CPU utilization
    private static final double SCALE_DOWN_THRESHOLD = 0.3; // 30% CPU utilization
    private static final int MAX_VMS = 5;
    private static final int MIN_VMS = 1;
    
    // Resource tracking
    private static int nextVmId = 0;
    private static int nextCloudletId = 0;
    private static Map<Double, Integer> scalingHistory = new TreeMap<>();

    public static void main(String[] args) {
        Log.printLine("Starting Enhanced Dynamic Auto-Scaling Simulation...");

        try {
            // Initialize CloudSim
            int numUser = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceFlag = false;
            CloudSim.init(numUser, calendar, traceFlag);

            // Create infrastructure
            Datacenter datacenter = createDatacenter("Datacenter_0");
            AutoScalingBroker broker = createBroker("AutoScalingBroker");
            
            // Create initial VM and cloudlets
            vmList = new ArrayList<>();
            Vm initialVm = createVm(broker.getId(), 1000, 1, 512, 1000, 10000, "Xen");
            vmList.add(initialVm);
            broker.submitVmList(vmList);

            // Create initial workload
            cloudletList = new ArrayList<>();
            createAndSubmitCloudlets(broker, broker.getId(), INITIAL_CLOUDLETS, 1);
            
            // Schedule dynamic workload changes
            scheduleWorkloadChanges(broker);
            
            // Set termination time to prevent premature shutdown
            CloudSim.terminateSimulation(SIMULATION_DURATION);
            
            // Start simulation
            CloudSim.startSimulation();

            // Output results
            printResults(broker.getCloudletReceivedList());
            printScalingHistory();
            
            CloudSim.stopSimulation();
            Log.printLine("Simulation completed successfully!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Simulation terminated due to error");
        }
    }

    private static Datacenter createDatacenter(String name) {
        // Host configuration
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(1000)));
        }

        Host host = new Host(
            0,
            new RamProvisionerSimple(16384),   // 16GB RAM
            new BwProvisionerSimple(10000),    // 10Gbps bandwidth
            1000000,                          // 1TB storage
            peList,
            new VmSchedulerTimeShared(peList)
        );

        // Datacenter characteristics
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
            "x86", "Linux", "Xen",
            Collections.singletonList(host),
            10.0, 3.0, 0.05, 0.001, 0.0);

        try {
            return new Datacenter(
                name, characteristics,
                new VmAllocationPolicySimple(Collections.singletonList(host)),
                new LinkedList<>(), 0);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static Vm createVm(int brokerId, int mips, int pes, int ram, 
                             long bw, long size, String vmm) {
        return new Vm(
            nextVmId++, brokerId, mips, pes, ram, bw, size, vmm,
            new CloudletSchedulerTimeShared());
    }

    private static AutoScalingBroker createBroker(String name) throws Exception {
        return new AutoScalingBroker(name);
    }

    private static void createAndSubmitCloudlets(AutoScalingBroker broker, int brokerId,
                                              int count, int pes) {
        List<Cloudlet> newCloudlets = new ArrayList<>();
        UtilizationModel utilizationModel = new UtilizationModelFull();
        
        for (int i = 0; i < count; i++) {
            Cloudlet cloudlet = new Cloudlet(
                nextCloudletId++, 10000, pes, 300, 300,
                utilizationModel, utilizationModel, utilizationModel);
            cloudlet.setUserId(brokerId);
            newCloudlets.add(cloudlet);
        }
        
        cloudletList.addAll(newCloudlets);
        broker.submitCloudletList(newCloudlets);
    }

    private static void scheduleWorkloadChanges(AutoScalingBroker broker) {
        final int LOAD_CHANGE = 10001;
        final int SCALING_CHECK = 10002;
        
        // Schedule workload changes
        CloudSim.send(broker.getId(), broker.getId(), 100, LOAD_CHANGE, CLOUDLETS_AT_100S);
        CloudSim.send(broker.getId(), broker.getId(), 200, LOAD_CHANGE, CLOUDLETS_AT_200S);
        CloudSim.send(broker.getId(), broker.getId(), 300, LOAD_CHANGE, CLOUDLETS_AT_300S);
        
        // Schedule periodic scaling checks every 20 seconds
        for (double time = 20; time < SIMULATION_DURATION; time += 20) {
            CloudSim.send(broker.getId(), broker.getId(), time, SCALING_CHECK, null);
        }
        
        broker.setCustomTags(LOAD_CHANGE, SCALING_CHECK);
    }

    private static void printResults(List<Cloudlet> cloudlets) {
        DecimalFormat df = new DecimalFormat("###.##");
        Log.printLine("\n========== CLOUDLET EXECUTION RESULTS ==========");
        Log.printLine("ID\tStatus\tDC\tVM\tTime\tStart\tFinish");
        
        for (Cloudlet c : cloudlets) {
            if (c.getCloudletStatus() == Cloudlet.SUCCESS) {
                Log.printLine(String.format(
                    "%d\t%s\t%d\t%d\t%s\t%s\t%s",
                    c.getCloudletId(), "SUCCESS", c.getResourceId(), c.getVmId(),
                    df.format(c.getActualCPUTime()), df.format(c.getExecStartTime()),
                    df.format(c.getFinishTime())
                ));
            }
        }
    }

    private static void printScalingHistory() {
        Log.printLine("\n========== AUTO-SCALING HISTORY ==========");
        Log.printLine("Time(s)\tVM Count");
        
        for (Map.Entry<Double, Integer> entry : scalingHistory.entrySet()) {
            Log.printLine(String.format("%.1f\t%d", entry.getKey(), entry.getValue()));
        }
    }

    static class AutoScalingBroker extends DatacenterBroker {
        private int loadChangeTag;
        private int scalingCheckTag;
        private List<Cloudlet> pendingCloudlets;
        private List<Vm> createdVmList;
        
        public AutoScalingBroker(String name) throws Exception {
            super(name);
            pendingCloudlets = new ArrayList<>();
            createdVmList = new ArrayList<>();
        }
        
        public void setCustomTags(int loadTag, int scalingTag) {
            this.loadChangeTag = loadTag;
            this.scalingCheckTag = scalingTag;
        }
        
        @Override
        protected void processOtherEvent(SimEvent ev) {
            if (ev.getTag() == loadChangeTag) {
                handleLoadChange((Integer) ev.getData());
            } else if (ev.getTag() == scalingCheckTag) {
                checkScaling();
            } else {
                super.processOtherEvent(ev);
            }
        }
        
        private void handleLoadChange(int cloudletCount) {
            Log.printLine(String.format("%.1f: Adding %d new cloudlets", 
                CloudSim.clock(), cloudletCount));
            createAndSubmitCloudlets(this, getId(), cloudletCount, 1);
            checkScaling();
        }
        
        @Override
        public void submitCloudletList(List<? extends Cloudlet> list) {
            super.submitCloudletList(list);
            
            for (Cloudlet cloudlet : list) {
                boolean assigned = false;
                
                if (!createdVmList.isEmpty()) {
                    Vm vm = findLeastLoadedVm();
                    if (vm != null) {
                        cloudlet.setVmId(vm.getId());
                        assigned = true;
                        Integer datacenterId = getVmsToDatacentersMap().get(vm.getId());
                        if (datacenterId != null) {
                            sendNow(datacenterId, CloudSimTags.CLOUDLET_SUBMIT, cloudlet);
                        } else {
                            Log.printLine(String.format("%.1f: Warning: No datacenter mapping for VM %d", 
                                CloudSim.clock(), vm.getId()));
                            assigned = false;
                        }
                    }
                }
                
                if (!assigned && !pendingCloudlets.contains(cloudlet)) {
                    pendingCloudlets.add(cloudlet);
                    Log.printLine(String.format("%.1f: Cloudlet %d added to pending list", 
                        CloudSim.clock(), cloudlet.getCloudletId()));
                }
            }
        }
        
        private Vm findLeastLoadedVm() {
            if (createdVmList.isEmpty()) return null;
            
            // Simple round-robin assignment
            int minLoad = Integer.MAX_VALUE;
            Vm selectedVm = null;
            
            for (Vm vm : createdVmList) {
                int vmLoad = getVmLoad(vm.getId());
                if (vmLoad < minLoad) {
                    minLoad = vmLoad;
                    selectedVm = vm;
                }
            }
            
            return selectedVm;
        }
        
        private int getVmLoad(int vmId) {
            int count = 0;
            for (Cloudlet c : getCloudletList()) {
                if (c.getVmId() == vmId && 
                    (c.getCloudletStatus() == Cloudlet.INEXEC || 
                     c.getCloudletStatus() == Cloudlet.QUEUED)) {
                    count++;
                }
            }
            return count;
        }
        
        @Override
        protected void processVmCreate(SimEvent ev) {
            int[] data = (int[]) ev.getData();
            if (data == null || data.length == 0) {
                Log.printLine(String.format("%.1f: Error in VM creation - no VM IDs returned", 
                    CloudSim.clock()));
                return;
            }
            
            for (int vmId : data) {
                Vm vm = findVmById(vmId);
                if (vm != null && !createdVmList.contains(vm)) {
                    createdVmList.add(vm);
                    Log.printLine(String.format("%.1f: VM #%d has been created", 
                        CloudSim.clock(), vm.getId()));
                    
                    // Update VM to datacenter mapping
                    if (!getVmsToDatacentersMap().containsKey(vm.getId())) {
                        getVmsToDatacentersMap().put(vm.getId(), ev.getSource());
                    }
                } else if (vm == null) {
                    Log.printLine(String.format("%.1f: Warning: Created VM #%d not found in submitted list", 
                        CloudSim.clock(), vmId));
                }
            }
            
            assignPendingCloudlets();
        }
        
        private Vm findVmById(int vmId) {
            for (Vm vm : getVmList()) {
                if (vm.getId() == vmId) {
                    return vm;
                }
            }
            return null;
        }
        
        private void assignPendingCloudlets() {
            if (pendingCloudlets.isEmpty() || createdVmList.isEmpty()) return;

            Iterator<Cloudlet> iterator = pendingCloudlets.iterator();
            while (iterator.hasNext()) {
                Cloudlet cloudlet = iterator.next();
                Vm vm = findLeastLoadedVm();

                if (vm != null) {
                    Integer datacenterId = getVmsToDatacentersMap().get(vm.getId());
                    
                    if (datacenterId != null) {
                        cloudlet.setVmId(vm.getId());
                        sendNow(datacenterId, CloudSimTags.CLOUDLET_SUBMIT, cloudlet);
                        iterator.remove();
                        Log.printLine(String.format("%.1f: Assigned pending cloudlet %d to VM %d", 
                            CloudSim.clock(), cloudlet.getCloudletId(), vm.getId()));
                    } else {
                        Log.printLine(String.format("%.1f: Warning: No datacenter mapping for VM %d", 
                            CloudSim.clock(), vm.getId()));
                    }
                }
            }
        }
        
        private void checkScaling() {
            int running = 0;
            int pending = pendingCloudlets.size();
            
            for (Cloudlet c : getCloudletList()) {
                if (c.getCloudletStatus() == Cloudlet.INEXEC) {
                    running++;
                } else if (c.getCloudletStatus() == Cloudlet.CREATED || 
                           c.getCloudletStatus() == Cloudlet.QUEUED) {
                    pending++;
                }
            }
            
            int createdVms = createdVmList.size();
            double utilization = createdVms > 0 ? (double)running / createdVms : 1.0;
            
            scalingHistory.put(CloudSim.clock(), createdVms);
            
            Log.printLine(String.format(
                "%.1f: Utilization=%.2f, VMs=%d, Running=%d, Pending=%d",
                CloudSim.clock(), utilization, createdVms, running, pending));
            
            if (createdVms == 0 && pending > 0) {
                scaleUp();
                return;
            }
            
            if ((utilization >= SCALE_UP_THRESHOLD || pending > createdVms * 2) && 
                createdVms < MAX_VMS && pending > 0) {
                scaleUp();
            } else if (utilization <= SCALE_DOWN_THRESHOLD && createdVms > MIN_VMS && pending == 0) {
                scaleDown();
            }
            
            assignPendingCloudlets();
        }

        private void scaleUp() {
            Log.printLine(String.format("%.1f: Scaling UP - creating new VM", CloudSim.clock()));
            
            Vm newVm = createVm(getId(), 1000, 1, 512, 1000, 10000, "Xen");
            List<Vm> tempVmList = new ArrayList<>();
            tempVmList.add(newVm);
            
            // Submit VM to broker
            submitVmList(tempVmList);
            
            // Determine datacenter ID
            int datacenterId = getFirstDatacenterId();
            
            // Send VM creation request to datacenter
            sendNow(datacenterId, CloudSimTags.VM_CREATE, newVm);
            
            // Add to global VM list
            vmList.add(newVm);
        }
        
        private int getFirstDatacenterId() {
            if (!getVmsToDatacentersMap().isEmpty()) {
                return getVmsToDatacentersMap().values().iterator().next();
            }
            return 2; // Default datacenter ID
        }
        
        private void scaleDown() {
            if (createdVmList.size() <= MIN_VMS) return;
            
            // Find the least utilized VM
            Vm toRemove = findLeastUtilizedVm();
            if (toRemove == null) return;
            
            Log.printLine(String.format("%.1f: Scaling DOWN - destroying VM %d", 
                CloudSim.clock(), toRemove.getId()));
            
            Integer datacenterId = getVmsToDatacentersMap().get(toRemove.getId());
            if (datacenterId != null) {
                sendNow(datacenterId, CloudSimTags.VM_DESTROY, toRemove);
                createdVmList.remove(toRemove);
                vmList.remove(toRemove);
                getVmsToDatacentersMap().remove(toRemove.getId());
            } else {
                Log.printLine(String.format("%.1f: Warning: No datacenter mapping for VM %d", 
                    CloudSim.clock(), toRemove.getId()));
            }
        }
        
        private Vm findLeastUtilizedVm() {
            if (createdVmList.isEmpty()) return null;
            
            Vm leastUtilized = null;
            double minUtilization = Double.MAX_VALUE;
            
            for (Vm vm : createdVmList) {
                double utilization = getVmUtilization(vm.getId());
                if (utilization < minUtilization) {
                    minUtilization = utilization;
                    leastUtilized = vm;
                }
            }
            
            return leastUtilized;
        }
        
        private double getVmUtilization(int vmId) {
            int running = 0;
            for (Cloudlet c : getCloudletList()) {
                if (c.getVmId() == vmId && c.getCloudletStatus() == Cloudlet.INEXEC) {
                    running++;
                }
            }
            return (double) running;
        }
        
        @Override
        protected void processCloudletReturn(SimEvent ev) {
            Cloudlet cloudlet = (Cloudlet) ev.getData();
            getCloudletReceivedList().add(cloudlet);
            Log.printLine(CloudSim.clock() + ": " + getName() + ": Cloudlet " + 
                cloudlet.getCloudletId() + " received");
            
            boolean allComplete = true;
            for (Cloudlet c : getCloudletSubmittedList()) {
                if (c.getCloudletStatus() != Cloudlet.SUCCESS && 
                    c.getCloudletStatus() != Cloudlet.FAILED) {
                    allComplete = false;
                    break;
                }
            }
            
            if (allComplete && pendingCloudlets.isEmpty() && 
                CloudSim.clock() > SIMULATION_DURATION - 50) {
                Log.printLine(CloudSim.clock() + ": " + getName() + 
                    ": All Cloudlets executed. Finishing...");
                
                for (Vm vm : new ArrayList<>(createdVmList)) {
                    Log.printLine(CloudSim.clock() + ": " + getName() + 
                        ": Destroying VM #" + vm.getId());
                    Integer datacenterId = getVmsToDatacentersMap().get(vm.getId());
                    if (datacenterId != null) {
                        sendNow(datacenterId, CloudSimTags.VM_DESTROY, vm);
                    }
                }
            }
        }
    }
}