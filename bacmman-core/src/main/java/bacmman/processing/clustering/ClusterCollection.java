/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.processing.clustering;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 *
 * @author Jean Ollion
 * @param <E> element data
 * @param <I> iterface data
 */
public class ClusterCollection<E, I extends Interface<E, I> > {
    public static boolean verbose;
    public static final Logger logger = LoggerFactory.getLogger(ClusterCollection.class);
    final Comparator<? super E> elementComparator;
    final TreeSet<I> interfaces;
    final HashMapGetCreate<E, Set<I>> interfaceByElement;
    final Collection<E> allElements;
    InterfaceFactory<E, I> interfaceFactory;
    
    public ClusterCollection(Collection<E> elements, Comparator<? super E> clusterComparator, InterfaceFactory<E, I> interfaceFactory) {
        this.elementComparator=clusterComparator;
        interfaceByElement = new HashMapGetCreate<>(elements.size(), new HashMapGetCreate.SetFactory());
        this.allElements=elements;
        this.interfaces = new TreeSet<>();
        this.interfaceFactory=interfaceFactory;
    }
    
    /*public ClusterCollection(Collection<E> elements, Map<Pair<E, E>, I> interactions, Comparator<? super E> clusterComparator, InterfaceFactory<I> interfaceFactory) {
        this(elements, clusterComparator, interfaceFactory);
        for (Entry<Pair<E, E>, I> e : interactions.entrySet()) addInteraction(e.getKey().key, e.getKey().value, e.getValue());
    }*/
    
    public I addInteraction(I i) {
        interfaces.add(i);
        interfaceByElement.getAndCreateIfNecessary(i.getE1()).add(i);
        interfaceByElement.getAndCreateIfNecessary(i.getE2()).add(i);
        return i;
    }
    
    public Set<I> getInterfaces(Collection<E> elements) {
        Set<I> res = new HashSet<>();
        elements.stream().filter((e) -> (interfaceByElement.containsKey(e))).forEach((e) -> {
            res.addAll(interfaceByElement.get(e));
        });
        return res;
    }
    public Set<I> getAllInterfaces() {
        return interfaces;
    }
    
    public Set<I> getInterfaces(E e) {
        if (interfaceByElement.containsKey(e)) return interfaceByElement.get(e);
        else return Collections.EMPTY_SET;
    }
    
    public Set<E> getInteractants(E e) {
        Set<I> inter =getInterfaces(e);
        Set<E> res = new HashSet<>(inter.size());
        for (I i : inter) res.add(i.getOther(e));
        return res;
    }
    
    public I getInterface(E e1, E e2, boolean createIfNull) {
        Collection<I> l = interfaceByElement.getAndCreateIfNecessary(e1);
        for (I i : l) if (i.isInterfaceOf(e2)) return i;
        if (createIfNull && interfaceFactory!=null) return addInteraction(interfaceFactory.create(getFirst(e1, e2), getSecond(e1, e2)));
        return null;
    }
    protected E getFirst(E e1, E e2) {
        if (elementComparator==null) return e1;
        int comp =  elementComparator.compare(e1, e2);
        return comp<=0 ? e1: e2;
    }
    protected E getSecond(E e1, E e2) {
        if (elementComparator==null) return e2;
        int comp =  elementComparator.compare(e1, e2);
        return comp<=0 ? e2: e1;
    }
    
    public List<Set<E>> getClusters() {
        List<Set<I>> interfaceClusters = new ArrayList<>();
        Set<I> currentCluster;
        //logger.debug("get interfaceClusters: # of interfaces {}", interfaces.size());
        for (I i : interfaces) {
            currentCluster = null;
            Collection<I> l1 = interfaceByElement.getAndCreateIfNecessary(i.getE1());
            Collection<I> l2 = interfaceByElement.getAndCreateIfNecessary(i.getE2());
            if (interfaceClusters.isEmpty()) {
                currentCluster = new HashSet<>(l1.size()+ l2.size()-1);
                currentCluster.addAll(l1);
                currentCluster.addAll(l2);
                interfaceClusters.add(currentCluster);
            } else {
                Iterator<Set<I>> it = interfaceClusters.iterator();
                while(it.hasNext()) {
                    Set<I> cluster = it.next();
                    if (cluster.contains(i)) {
                        cluster.addAll(l1);
                        cluster.addAll(l2);
                        if (currentCluster!=null) { // fusionInterface des interfaceClusters
                            currentCluster.addAll(cluster);
                            it.remove();
                        } else currentCluster=cluster;
                    }
                }
                if (currentCluster==null) {
                    currentCluster = new HashSet<I>(l1.size()+ l2.size());
                    currentCluster.addAll(l1);
                    currentCluster.addAll(l2);
                    interfaceClusters.add(currentCluster);
                }
            }
        }
        // creation des clusters d'objets 
        List<Set<E>> clusters = new ArrayList<>();
        for (Set<I> iSet : interfaceClusters) {
            Set<E> eSet = new HashSet<>();
            for (I i : iSet) {
                eSet.add(i.getE1());
                eSet.add(i.getE2());
            }
            clusters.add(eSet);
        }
        // ajout des elements isolés
        for (E e : allElements) if (!interfaceByElement.containsKey(e) || interfaceByElement.get(e).isEmpty()) clusters.add(new HashSet<E>(){{add(e);}});
        return clusters;
    }
    
    
    
    /*public List<E> mergeSortCluster(Fusion<E, I> fusionInterface, InterfaceSortValue<E, I> interfaceSortValue) {
        List<Set<Interface<E, I>>> interfaceClusters = getClusters();
        // create one ClusterCollection per cluster and apply mergeSort
    }*/
    Predicate<I> forbidFusion = null;
    public void addForbidFusionPredicate(Predicate<I> forbidFusion) {
        if (forbidFusion==null) return;
        if (this.forbidFusion!=null) this.forbidFusion = this.forbidFusion.or(forbidFusion);
        else this.forbidFusion=forbidFusion;
    }
    public BooleanSupplier interfaceNumberStopCondition(int numberOfInterfacesToKeep) {
        return () -> interfaces.size()<=numberOfInterfacesToKeep;
    }
    public BooleanSupplier elementNumberStopCondition(int numberOfElementsToKeep) {
        return () -> allElements.size()<=numberOfElementsToKeep;
    }
    public static BooleanSupplier or(BooleanSupplier... condition) {
        switch(condition.length) {
            case 0:
                return () -> true;
            case 1:
                return condition[0];
            default:
                return () -> Arrays.stream(condition).anyMatch(b->b.getAsBoolean());
        }
    }
    public List<E> mergeSort(boolean checkCriterion, BooleanSupplier stopCondition) {
        if (stopCondition==null) stopCondition = () -> false;
        long t0 = System.currentTimeMillis();
        Set<I> allInterfaces = new HashSet<>(interfaces);
        for (I i : allInterfaces) i.updateInterface();
        interfaces.clear();
        interfaces.addAll(allInterfaces); // update sort values...
        int interSize = interfaces.size();
        if (verbose) {
            for (I i : interfaces) logger.debug("interface: {}", i);
            for (E e : interfaceByElement.keySet()) logger.debug("Element: {}, interfaces: {}", e, interfaceByElement.get(e));
        }
        if (interSize!=interfaces.size()) throw new RuntimeException("Error INCONSISTENCY BETWEEN COMPARE AND EQUALS METHOD FOR INTERFACE CLASS");
        //List<I> interfaces = new ArrayList<>(this.interfaces);
        //Collections.sort(interfaces);
        Iterator<I> it = interfaces.iterator();
        while (it.hasNext() && !stopCondition.getAsBoolean()) {
            I i = it.next();
            if (forbidFusion!=null && forbidFusion.test(i)) continue; // test each time & do not remove interface as the test could change after fusions
            if (!checkCriterion || i.checkFusion() ) {
                if (verbose) logger.debug("fusion {}", i);
                it.remove();
                allElements.remove(i.getE2());
                i.performFusion();
                if (updateInterfacesAfterFusion(i)) { // if any change in the interface treeset, recompute the iterator
                    //Collections.sort(interfaces);
                    it=interfaces.iterator();
                } 
                if (false && verbose) {
                    logger.debug("bilan/");
                    for (I ii : interfaces) logger.debug("interface: {}", ii);
                    for (E e : interfaceByElement.keySet()) logger.debug("Element: {}, interfaces: {}", e, interfaceByElement.get(e));
                    logger.debug("/bilan");
                }
            } //else if (i.hasOneRegionWithNoOtherInteractant(this)) it.remove(); // won't be modified so no need to test once again
        }
        long t1 = System.currentTimeMillis();
        if (verbose) logger.debug("Merge sort: total time : {} total interfaces: {} after merge: {}", t1-t0, interfaces.size(), interfaces.size());
        
        return new ArrayList<>(interfaceByElement.keySet());
    }   
    protected void removeInterface(I i) {
        Collection<I> l1 = interfaceByElement.get(i.getE1());
        Collection<I> l2 = interfaceByElement.remove(i.getE2());
        if (l1!=null) l1.remove(i);
        if (l2!=null) l2.remove(i);
    }
    /**
     * Update all connected interface after a fusion
     * @param i
     * @return true if changes were made in the interfaces set
     */
    protected boolean updateInterfacesAfterFusion(I i) {
        Collection<I> l1 = interfaceByElement.get(i.getE1());
        Collection<I> l2 = interfaceByElement.remove(i.getE2());
        boolean change = false;
        
        if (l2!=null) {
            for (I otherInterface : l2) { // appends interfaces of deleted region (e2) to new region (e1)
                if (!otherInterface.equals(i)) {
                    change = true;
                    E otherElement = otherInterface.getOther(i.getE2());
                    I existingInterface=null;
                    if (l1!=null) existingInterface = Utils.getFirst(l1, j->j.isInterfaceOf(i.getE1(), otherElement)); // look for existing interface between e1 and otherElement
                    if (existingInterface!=null) { // if interface is already present in e1, simply merge the interfaces
                        if (verbose) logger.debug("merge {} with {}", existingInterface, otherInterface);
                        remove(interfaces, otherInterface);
                        if (interfaces instanceof Set) remove(interfaces, existingInterface);// sort value will change 
                        Collection<I> otherInterfaces = interfaceByElement.get(otherElement);
                        if (otherInterfaces!=null) otherInterfaces.remove(otherInterface); // will be replaced by existingInterface
                        
                        existingInterface.fusionInterface(otherInterface, elementComparator);
                        existingInterface.updateInterface();
                        if (interfaces instanceof Set) interfaces.add(existingInterface); // sort value changed 
                        // no need to add and remove from interfaces of e1 and otherElement beause hashCode hasnt changed
                        
                    } else { // if not add a new interface between E1 and otherElement
                        if (verbose) logger.debug("switch {}", otherInterface);
                        if (interfaces instanceof Set) remove(interfaces,otherInterface); // hashCode will change because of switch
                        Collection<I> otherInterfaces = interfaceByElement.get(otherElement);
                        if (otherInterfaces!=null) otherInterfaces.remove(otherInterface); // hashCode will change because of switch
                        
                        otherInterface.swichElements(i.getE1(), i.getE2(), elementComparator);
                        otherInterface.updateInterface(); // TODO should be called in the method only if necessary, depending on interface ? 
                        if (interfaces instanceof Set) interfaces.add(otherInterface);
                        if (otherInterfaces!=null) otherInterfaces.add(otherInterface);
                        if (l1!=null) l1.add(otherInterface);
                    }
                }
            }
        }
        if (l1!=null) {  // e1 has change so update all his interfaces // perform updates after removing interfaces, if not may not be removed
            l1.remove(i);
            if (!l1.isEmpty()) change = true; 
            for (I otherI : l1) otherI.updateInterface();
        }
        return change;
    }
    private static  <I> void remove(Collection<I> col, I i) {
        boolean r = col.remove(i);
        if (!r) col.removeIf(in->in.equals(i));
    }

    public static interface InterfaceFactory<E, T extends Interface<E, T>> {
        public T create(E e1, E e2);
    }
    
}
