package purpleOpt;
import java.util.*;

/*
input:
	orders:
		order:
			lat (Double);
			lng (Double);
			gas_type (String);
			gallons (Integer);
			target_time_start (Long);
			target_end_start (Long);
			status_times (HashMap<String,Long>):
				String: (Long);
	couriers:
		courier:
			lat (Double);
			lng (Double);
			on_duty (Boolean);
			assigned_orders (List<String>);
*/

public class PurpleOpt {
//	public static PurpleOptStatus computeAssignment(
//			HashMap<Integer,PurpleCourier> couriers, 
//			HashMap<Integer,PurpleOrder> orders,
//			PurpleOptStatus status) {
//		if (status == null) {
//			status = new PurpleOptStatus(couriers, orders);
//			return status;
//		} 
//		else {
//			return status.computeAssignment(couriers, orders);
//		}
//	}

    public static String testSim(String arg1) {
        System.out.println("testSim");
        System.out.println(arg1);
        
        return "K";
    }
    
	public static String computeDistance(HashMap<String,Object> input) {
		@SuppressWarnings("serial")
		class InCourier extends HashMap<String, Object> { };
		@SuppressWarnings("serial")
		class InCouriers extends HashMap<String, InCourier> { };
		@SuppressWarnings("serial")
		class InOrder extends HashMap<String, Object> { };
		@SuppressWarnings("serial")
		class InOrders extends HashMap<String, InOrder> { };
		
		// --- read data from input to structures that are easy to use ---
		
		// list keys in input
		System.out.println("Keys in the input: ");
		for(String key: input.keySet()) {
			System.out.print(key + "; ");
		}
		System.out.println();
		
		// read orders
		InOrders orders = (InOrders) input.get("orders");
		int nOrders = orders.size();
		System.out.println("# orders: " + nOrders);
		
		// for each order
		for(String order_key: orders.keySet()) {
			// print order ID
			System.out.println("  order: " + order_key);
			// get the order by ID (key)
			InOrder order = orders.get(order_key);
			// list the keys of each order
			System.out.println("  keys in this order: ");
			for(String key: order.keySet()) {
				System.out.print(key + "; ");
			}
			System.out.println();
			// print order content manually
			System.out.println("    lat: " + (Double) order.get("lat"));
			System.out.println("    lng: " + (Double) order.get("lng"));
			System.out.println("    gas_type: " + (String) order.get("gas_type"));
			System.out.println("    gallons: " + (Integer) order.get("gallons"));
			System.out.println("    target_time_start: " + (Long) order.get("target_time_start"));
			System.out.println("    target_time_end : " + (Long) order.get("target_time_end "));
			System.out.println("    status: " + (String) order.get("status"));
			
			// print the status history
			@SuppressWarnings("unchecked")
			HashMap<String,Long> status_times = (HashMap<String,Long>) order.get("status_times");
			
			System.out.println("    key:value pairs in this status_times: ");
			for(String timekey: status_times.keySet()) {
				System.out.print(timekey + ": " + (Long) status_times.get(timekey) +"; ");
			}
		}
		
		// read couriers
		InCouriers couriers = (InCouriers) input.get("couriers");
		int nCouriers = couriers.size();
		System.out.println("# of couriers: " + nCouriers);
		
		// for each courier
		for(String courier_key: couriers.keySet()) {
			// print courier ID
			System.out.println("  courier: " + courier_key);
			// get the order by ID (key)
			InCourier courier = couriers.get(courier_key);
			// list the keys of each courier_key
			System.out.println("  keys in this couriers: ");
			for(String key: courier.keySet()) {
				System.out.print(key + "; ");
			}
			System.out.println();
			// print courier content manually
			System.out.println("    lat: " + (Double) courier.get("lat"));
			System.out.println("    lng: " + (Double) courier.get("lng"));
			System.out.println("    on_duty: " + (Boolean) courier.get("on_duty"));
			
			// print assiged_orders
			System.out.println("    assigned_orders:" );
			@SuppressWarnings("unchecked")
			List<String> assigned_orders = (List<String>) courier.get("assigned_orders");
			for(String assigned_order: assigned_orders) {
				System.out.println(assigned_order + " ");
			}
		}
		
		// compute Google distances 
		// create output data structure
		
		return "OK";
	}
}

