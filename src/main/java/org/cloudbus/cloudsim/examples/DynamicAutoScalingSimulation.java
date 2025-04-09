
package org.cloudbus.cloudsim.examples;

import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.examples.DynamicAutoScalingSimulation.AutoScalingBroker;
import org.cloudbus.cloudsim.provisioners.*;

public class DynamicAutoScalingSimulation {

	public static void main(String[] args) {
        try {
            // Initialize CloudSim
            CloudSim.init(1, Calendar.getInstance(), false);

            // Create datacenter with one host
            List<Pe> peList = Collections.singletonList(new Pe(0, new PeProvisionerSimple(1000)));
            Host host = new Host(
                0, 
                new RamProvisionerSimple(2048),
                new BwProvisionerSimple(10000),
                1000000,
                peList,
                new VmSchedulerTimeShared(peList)
            );

            Datacenter dc = new Datacenter(
                "Datacenter",
                new DatacenterCharacteristics("x86", "Linux", "Xen", 
                    Collections.singletonList(host), 10.0, 3.0, 0.05, 0.001, 0.0),
                new VmAllocationPolicySimple(Collections.singletonList(host)),
                new LinkedList<>(),
                0
            );

            // Create broker
            DatacenterBroker broker = new DatacenterBroker("Broker") {
                private int vmId = 0;
                
                @Override
                protected void processOtherEvent(SimEvent ev) {
                    if (ev.getTag() == 100) { // Scaling check
                        int runningCloudlets = 0;
                        int activeVms = getVmList().size();
                        
                        for (Cloudlet c : getCloudletList()) {
                            if (c.getCloudletStatus() == Cloudlet.INEXEC) runningCloudlets++;
                        }
                        
                        // Scale up if needed
                        if (runningCloudlets > activeVms && activeVms < 3) {
                            Vm vm = new Vm(vmId++, getId(), 1000, 1, 512, 1000, 10000, "Xen", 
                                         new CloudletSchedulerTimeShared());
                            submitVmList(Collections.singletonList(vm));
                            System.out.printf("%.1f: Added VM %d%n", CloudSim.clock(), vm.getId());
                        }
                        // Scale down if needed
                        else if (runningCloudlets < activeVms/2 && activeVms > 1) {
                            Vm toRemove = getVmList().get(getVmList().size()-1);
                            sendNow(getVmsToDatacentersMap().get(toRemove.getId()), 
                                  CloudSimTags.VM_DESTROY, toRemove);
                            getVmList().remove(toRemove);
                            System.out.printf("%.1f: Removed VM %d%n", CloudSim.clock(), toRemove.getId());
                        }
                    }
                }
            };

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
                    new UtilizationModelFull()
                );
                cloudlet.setUserId(broker.getId());
                broker.submitCloudletList(Collections.singletonList(cloudlet));
            }

            // Schedule scaling checks
            for (double time = 20; time < 200; time += 20) {
                CloudSim.send(broker.getId(), broker.getId(), time, 100, null);
            }

            // Run simulation
            CloudSim.startSimulation();
            CloudSim.stopSimulation();
            
            System.out.println("Simulation completed successfully!");
            
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Simulation failed");
        }
    }
}