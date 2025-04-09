package org.cloudbus.cloudsim.examples;

import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.provisioners.*;

public class DynamicAutoScalingSimulation {

    // Auto-scaling configuration
    private static final double SCALE_UP_THRESHOLD = 0.8;
    private static final double SCALE_DOWN_THRESHOLD = 0.3;
    private static final int MAX_VMS = 5;
    private static final int MIN_VMS = 1;
    

    public static void main(String[] args) {
        Log.printLine("Starting Dynamic Auto-Scaling Simulation...");

        try {
            // Initialize CloudSim
            CloudSim.init(1, Calendar.getInstance(), false);

            // Create datacenter
            Datacenter datacenter = createDatacenter("Datacenter_0");

            // Create broker
            AutoScalingBroker broker = new AutoScalingBroker("AutoScalingBroker");

            // Create initial VM
            Vm vm = new Vm(0, broker.getId(), 1000, 1, 512, 1000, 10000, "Xen",
                          new CloudletSchedulerTimeShared());
            broker.submitVmList(Collections.singletonList(vm));

            // Create initial cloudlets
            for (int i = 0; i < 5; i++) {
                Cloudlet cloudlet = new Cloudlet(
                    i, 10000, 1, 300, 300,
                    new UtilizationModelFull(),
                    new UtilizationModelFull(),
                    new UtilizationModelFull());
                cloudlet.setUserId(broker.getId());
                broker.submitCloudletList(Collections.singletonList(cloudlet));
            }
            

            // Schedule scaling checks every 20 seconds
            for (double time = 20; time < 200; time += 20) {
                CloudSim.send(broker.getId(), broker.getId(), time, 1001 + 1, null);
            }

            // Start simulation
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            Log.printLine("Simulation completed!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Simulation terminated due to error");
        }
    }

    private static Datacenter createDatacenter(String name) {
        // Create host
        List<Pe> peList = Collections.singletonList(new Pe(0, new PeProvisionerSimple(1000)));
        Host host = new Host(
            0,
            new RamProvisionerSimple(2048),
            new BwProvisionerSimple(10000),
            1000000,
            peList,
            new VmSchedulerTimeShared(peList));

        // Create datacenter
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

    static class AutoScalingBroker extends DatacenterBroker {
        private int nextVmId = 1; // Start from 1 since 0 is used for initial VM

        public AutoScalingBroker(String name) throws Exception {
            super(name);
        }

        @Override
        protected void processOtherEvent(SimEvent ev) {
            if (ev.getTag() == 1001 + 1) { // Scaling check
                checkScaling();
            }
        }

        private void checkScaling() {
            int runningCloudlets = 0;
            int activeVms = getVmList().size();

            // Count running cloudlets
            for (Cloudlet c : getCloudletList()) {
                if (c.getCloudletStatus() == Cloudlet.INEXEC) {
                    runningCloudlets++;
                }
            }

            double utilization = activeVms > 0 ? (double) runningCloudlets / activeVms : 0;

            Log.printLine(String.format(
                "%.1f: Utilization=%.2f, VMs=%d, Running=%d",
                CloudSim.clock(), utilization, activeVms, runningCloudlets));

            // Scale up if needed
            if (utilization >= SCALE_UP_THRESHOLD && activeVms < MAX_VMS) {
                scaleUp();
            }
            // Scale down if needed
            else if (utilization <= SCALE_DOWN_THRESHOLD && activeVms > MIN_VMS) {
                scaleDown();
            }
        }

        private void scaleUp() {
            Vm vm = new Vm(
                nextVmId++, getId(), 1000, 1, 512, 1000, 10000, "Xen",
                new CloudletSchedulerTimeShared());
            submitVmList(Collections.singletonList(vm));
            Log.printLine(String.format("%.1f: Added VM %d", CloudSim.clock(), vm.getId()));
        }

        private void scaleDown() {
            if (getVmList().size() <= MIN_VMS) return;

            Vm toRemove = getVmList().get(getVmList().size() - 1);
            sendNow(getVmsToDatacentersMap().get(toRemove.getId()),
                  CloudSimTags.VM_DESTROY, toRemove);
            getVmList().remove(toRemove);
            Log.printLine(String.format("%.1f: Removed VM %d", CloudSim.clock(), toRemove.getId()));
        }
    }
}