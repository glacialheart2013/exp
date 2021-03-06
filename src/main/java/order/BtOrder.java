package order;

import java.text.SimpleDateFormat;
import java.util.*;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;

import model.*;
import data.*;

import java.text.ParseException;

/**
 * Back-testing order. 
 * Used to open market order or pending order.
 * Order, Position and Transaction info is stored in the database.
 * 
 * @author Zhongqiang Shen
 */
public class BtOrder extends Order  {
	/**
	 * Constructor
	 * 
	 * @param session - hibernate session, used to interact with database
	 * @param account - the account {@link Account} that the order actions are associated with
	 */
	public BtOrder(Session session, Account account) {
		this.session = session;
		this.account = account;
	}
	
	/**
	 * Check if there is open position
	 * 
	 * @param product - product (e.g. EURUSD)
	 * @return true if has open position, otherwise false 
	 */
	public boolean HasPosition(String product) {
		Transaction tx = this.session.beginTransaction();
		Query q = session.createQuery("from Position where account.id = :id and product = :product");
		q.setParameter("id", account.getId());
		q.setParameter("product",product);
		
		List list = q.list();
		boolean hasPosition = false;
		if(list.size() > 0) {
			hasPosition = true;
		}
		tx.commit();
		return hasPosition;
	}
	
	/**
	 * Get open positions of a specified product.
	 * For a certain product, there should be at most one open position.
	 * 
	 * @param product - product (e.g. EURUSD)
	 * @return Position {@link Position} of that product
	 */
	public Position getPosition(String product) {
		Transaction tx = this.session.beginTransaction();
		Query q = session.createQuery("from Position where account.id = :id and product = :product");
		q.setParameter("id", account.getId());
		q.setParameter("product", product);
		List list = q.list();
		Position p = null;
		if(list.size() > 0) {
			p = (Position) list.get(0);
		}
		tx.commit();
		return p;
	}
	
	/**
	 * Open market buy order
	 * 
	 * @param product - product to buy (e.g. EURUSD)
	 * @param strTime - time of when the order is submitted
	 * @param price - the ask price to buy
	 * @param amount - amount to buy
	 */
	public void MarketBuy(String product, String strTime, double price, int amount) {
		Transaction tx = this.session.beginTransaction();
		Query q = session.createQuery("from Position where account.id = :id and product = :product");
		q.setParameter("id", account.getId());
		q.setParameter("product",product);
		List list = q.list();
		tx.commit();
		
		if(list.size() > 0) {
			Position p = (Position) list.get(0);
			int totalAmount = p.getAmount() + amount;
			if(totalAmount == 0) {
				tx = session.beginTransaction();
				session.delete(p);
				tx.commit();
			}
			else {
				p.setAmount(totalAmount);
				tx = session.beginTransaction();
				session.update(p);
				tx.commit();
			}
		}
		else {
			Position p = new Position(this.account, product, amount);
			tx = session.beginTransaction();
			session.save(p);
			tx.commit();
		}

		this.SaveBuyTransaction(strTime, product, price, amount);
		System.out.println(String.format("%s - buy %d mini lot %s at %f", strTime, amount, product, price));
}
	
	/**
	 * Open market sell order
	 * 
	 * @param product - product to sell (e.g. EURUSD)
	 * @param strTime - time of when the order is submitted
	 * @param price - the bid price to sell
	 * @param amount - amount to sell
	 */
	public void MarketSell(String product, String strTime, double price, int amount) {
		Transaction tx = this.session.beginTransaction();
		Query q = session.createQuery("from Position where account.id = :id and product = :product");
		q.setParameter("id", account.getId());
		q.setParameter("product",product);
		List list = q.list();
		tx.commit();
		
		if(list.size() > 0) {
			Position p = (Position) list.get(0);
			int totalAmount = p.getAmount() - amount;
			if(totalAmount == 0) {
				tx = session.beginTransaction();
				session.delete(p);
				tx.commit();
			}
			else {
				p.setAmount(totalAmount);
				tx = session.beginTransaction();
				session.update(p);
				tx.commit();
			}

		}
		else {
			Position p = new Position(this.account, product, amount * -1);
			tx = session.beginTransaction();
			session.save(p);
			tx.commit();
		}
		
		this.SaveSellTransaction(strTime, product, price, amount);
		System.out.println(String.format("%s - sell %d mini lot %s at %f", strTime, amount, product, price));
	}
	
	/**
	 * Save buy transaction
	 * 
	 * @param strTime - time of when the transaction is saved
	 * @param product - product to buy (e.g. EURUSD)
	 * @param price - the bid price to buy
	 * @param amount - amount to buy
	 */
	private void SaveBuyTransaction(String strTime, String product, double price, int amount) {
		Transaction tx = session.beginTransaction();
		Query q = session.createQuery("from OpenTransaction where account.id = :id order by time asc");
		q.setParameter("id", account.getId());
		List list = q.list();
		tx.commit();

		try {
			SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			Date time = ft.parse(strTime);
		
			if(list.size() == 0) {
				// if no open transactions, add this transaction to open transaction and transaction history
				OpenTransaction ot = new OpenTransaction(account, time, product, price, amount);
				TransactionHistory th = new TransactionHistory(account, time, product, price, amount);
				
				tx = session.beginTransaction();
				session.save(ot);
				session.save(th);
				tx.commit();
			}
			else {
				// if there is open transactions, first determine the direction of the open transactions
				OpenTransaction first = (OpenTransaction) list.get(0);
				if(first.getAmount() > 0) {
					// if open transactions are long, add this transaction to open transaction and transaction history
					OpenTransaction ot = new OpenTransaction(account, time, product, price, amount);
					TransactionHistory th = new TransactionHistory(account, time, product, price, amount);
					tx = session.beginTransaction();
					session.save(ot);
					session.save(th);
					tx.commit();
					//System.out.println("added position - added open transaction");
				}
				else {
					// if open transactions are short, close open transactions
					int remainAmount = amount;
					Iterator<OpenTransaction> j = list.iterator();
					
					double totalPl = 0;
					while(j.hasNext() && remainAmount > 0) {
						OpenTransaction item = j.next();
						if(remainAmount + item.getAmount() >= 0) {
							double pl = item.getAmount() * 1000 * (price - item.getPrice());
							totalPl += pl;
							remainAmount += item.getAmount();
							tx = session.beginTransaction();
							session.delete(item);
							tx.commit();
							//System.out.println("close position - removed open transaction");
						}
						else {
							double pl = remainAmount * -1 * 1000 * (price - item.getPrice());
							totalPl += pl;
							item.setAmount(remainAmount + item.getAmount());
							remainAmount = 0;
							tx = session.beginTransaction();
							session.update(item);
							tx.commit();
							//System.out.println("close position - updated open transaction");
						}
					}
					
					// if after closing all open transactions, 
					// there is still remaining amount in the long transaction,
					// add that long transaction with remaining amount to the open transaction
					if(remainAmount > 0) {
						tx = session.beginTransaction();
						OpenTransaction ot = new OpenTransaction(account, time, product, price, remainAmount);
						session.save(ot);
						tx.commit();
					}
					
					account.setBalance(account.getBalance() + totalPl);
					TransactionHistory th = new TransactionHistory(account, time, product, price, amount);
					tx = session.beginTransaction();
					session.update(account);
					session.save(th);
					tx.commit();
				}
			}
		}
		catch(ParseException ex) {
			System.out.println("Error occurred when parsing " + strTime);
			ex.printStackTrace();
		}
		
	}
	
	/**
	 * Save sell transaction
	 * 
	 * @param strTime - time of when the transaction is saved
	 * @param product - product to sell (e.g. EURUSD)
	 * @param price - the bid price to sell
	 * @param amount - amount to sell
	 */
	private void SaveSellTransaction(String strTime, String product, double price, int amount) {
		Transaction tx = session.beginTransaction();
		Query q = session.createQuery("from OpenTransaction where account.id = :id order by time asc");
		q.setParameter("id", account.getId());
		List list = q.list();
		tx.commit();
		
		try {
			SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			Date time = ft.parse(strTime);
		
			if(list.size() == 0) {
				// if no open transactions, add this transaction to open transaction and transaction history
				OpenTransaction ot = new OpenTransaction(account, time, product, price, amount * -1);
				TransactionHistory th = new TransactionHistory(account, time, product, price, amount * -1);
				
				tx = session.beginTransaction();
				session.save(ot);
				session.save(th);
				tx.commit();
				//System.out.println("open position - added open transaction");
			}
			else {
				// if there is open transactions, first determine the direction of the open transactions
				OpenTransaction first = (OpenTransaction) list.get(0);
				if(first.getAmount() < 0) {
					// if open transactions are short, add this transaction to open transaction and transaction history
					OpenTransaction ot = new OpenTransaction(account, time, product, price, amount * -1);
					TransactionHistory th = new TransactionHistory(account, time, product, price, amount * -1);
					tx = session.beginTransaction();
					session.save(ot);
					session.save(th);
					tx.commit();
					//System.out.println("add position - added open transaction");
				}
				else {
					// if open transactions are long, close open transactions
					int remainAmount = amount * -1;
					Iterator<OpenTransaction> j = list.iterator();
					
					double totalPl = 0;
					while(j.hasNext() && remainAmount < 0) {
						OpenTransaction item = j.next();
						if(remainAmount + item.getAmount() <= 0) {
							double pl = item.getAmount() * 1000 * (price - item.getPrice());
							totalPl += pl;
							remainAmount += item.getAmount();
							tx = session.beginTransaction();
							session.delete(item);
							tx.commit();
							//System.out.println("close position - removed open transaction");
						}
						else {
							double pl = remainAmount * -1 * 1000 * (price - item.getPrice());
							totalPl += pl;
							item.setAmount(remainAmount + item.getAmount());
							remainAmount = 0;
							tx = session.beginTransaction();
							session.update(item);
							tx.commit();
							//System.out.println("close position - updated open transaction");
						}
					}
					
					// if after closing all open transactions, 
					// there is still remaining amount in the long transaction,
					// add that long transaction with remaining amount to the open transaction
					if(remainAmount > 0) {
						tx = session.beginTransaction();
						OpenTransaction ot = new OpenTransaction(account, time, product, price, remainAmount);
						session.save(ot);
						tx.commit();
					}
					
					account.setBalance(account.getBalance() + totalPl);
					TransactionHistory th = new TransactionHistory(account, time, product, price, amount * -1);
					tx = session.beginTransaction();
					session.update(account);
					session.save(th);
					tx.commit();
				}
			}
		}
		catch(ParseException ex) {
			System.out.println("Error occurred when parsing " + strTime);
			ex.printStackTrace();
		}
	}
	
	/**
	 * Open stop buy order
	 * 
	 * @param product - product to buy (e.g. EURUSD)
	 * @param strTime - time of when the order is submitted
	 * @param stopPrice - the stop price to buy
	 * @param amount - amount to buy
	 */
	public void StopBuy(String product, String strTime, double stopPrice, int amount) {
		Transaction tx = session.beginTransaction();
		try {
			SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			Date time = ft.parse(strTime);
			PendingOrder po = new PendingOrder(this.account, time, product, stopPrice, amount, "stop");
			session.save(po);
			tx.commit();
		}
		catch(ParseException ex) {
			tx.rollback();
			System.out.println("Error occurred when parsing " + strTime);
			ex.printStackTrace();
		}
	}
	
	/**
	 * Open limit buy order
	 * 
	 * @param product - product to buy (e.g. EURUSD)
	 * @param strTime - time of when the order is submitted
	 * @param limitPrice - the limit price to buy
	 * @param amount - amount to buy
	 */
	public void LimitBuy(String product, String strTime, double limitPrice, int amount) {
		Transaction tx = session.beginTransaction();
		try {
			SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			Date time = ft.parse(strTime);
			PendingOrder po = new PendingOrder(this.account, time, product, limitPrice, amount, "limit");
			session.save(po);
			tx.commit();
		}
		catch(ParseException ex) {
			tx.rollback();
			System.out.println("Error occurred when parsing " + strTime);
			ex.printStackTrace();
		}
	}
	
	/**
	 * Open stop sell order
	 * 
	 * @param product - product to sell (e.g. EURUSD)
	 * @param strTime - time of when the order is submitted
	 * @param stopPrice - the stop price to sell
	 * @param amount - amount to sell
	 */
	public void StopSell(String product, String strTime, double stopPrice, int amount) {
		Transaction tx = session.beginTransaction();
		try {
			SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			Date time = ft.parse(strTime);
			PendingOrder po = new PendingOrder(this.account, time, product, stopPrice, amount * -1, "stop");
			session.save(po);
			tx.commit();
		}
		catch(ParseException ex) {
			tx.rollback();
			System.out.println("Error occurred when parsing " + strTime);
			ex.printStackTrace();
		}
	}
	
	/**
	 * Open limit sell order
	 * 
	 * @param product - product to sell (e.g. EURUSD)
	 * @param strTime - time of when the order is submitted
	 * @param limitPrice - the limit price to sell
	 * @param amount - amount to sell
	 */
	public void LimitSell(String product, String strTime, double limitPrice, int amount) {
		Transaction tx = session.beginTransaction();
		try {
			SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			Date time = ft.parse(strTime);
			PendingOrder po = new PendingOrder(this.account, time, product, limitPrice, amount * -1, "limit");
			session.save(po);
			tx.commit();
		}
		catch(ParseException ex) {
			tx.rollback();
			System.out.println("Error occurred when parsing " + strTime);
			ex.printStackTrace();
		}
	}
	
	/**
	 * Get all pending orders
	 * 
	 * @param product - the product (e.g. EURUSD)
	 * @return list {@link List} of pending orders 
	 */
	public List getPendingOrders(String product) {
		Transaction tx = session.beginTransaction();
		Query q = session.createQuery("from PendingOrder where product = :product");
		q.setParameter("product", product);
		List list = q.list();
		tx.commit();
		return list;
	}
		
	/**
	 * Get all stop buy orders
	 * 
	 * @param product - the product (e.g. EURUSD)
	 * @return list {@link List} of stop buy orders 
	 */
	public List getStopBuyOrders(String product) {
		Transaction tx = session.beginTransaction();
		Query q = session.createQuery("from PendingOrder where amount > 0 and product = :product and type = :type");
		q.setParameter("type", "stop");
		q.setParameter("product", product);
		List list = q.list();
		tx.commit();
		return list;
	}
	
	/**
	 * Get all limit buy orders
	 * 
	 * @param product - the product (e.g. EURUSD)
	 * @return list {@link List} of limit buy orders 
	 */
	public List getLimitBuyOrders(String product) {
		Transaction tx = session.beginTransaction();
		Query q = session.createQuery("from PendingOrder where amount > 0 and product = :product and type = :type");
		q.setParameter("type", "limit");
		q.setParameter("product", product);
		List list = q.list();
		tx.commit();
		return list;
	}
	
	/**
	 * Get all stop sell orders
	 * 
	 * @param product - the product (e.g. EURUSD)
	 * @return list {@link List} of stop sell orders 
	 */
	public List getStopSellOrders(String product) {
		Transaction tx = session.beginTransaction();
		Query q = session.createQuery("from PendingOrder where amount < 0 and product = :product and type = :type");
		q.setParameter("type", "stop");
		q.setParameter("product", product);
		List list = q.list();
		tx.commit();
		return list;
	}
	
	/**
	 * Get all limit sell orders
	 * 
	 * @param product - the product (e.g. EURUSD)
	 * @return list {@link List} of limit sell orders 
	 */
	public List getLimitSellOrders(String product) {
		Transaction tx = session.beginTransaction();
		Query q = session.createQuery("from PendingOrder where amount < 0 and product = :product and type = :type");
		q.setParameter("type", "limit");
		q.setParameter("product", product);
		List list = q.list();
		tx.commit();
		return list;
	}
	
	/**
	 * Update amount and price of the specified pending order
	 * 
	 * @param po - the specified pending order
	 * @param amount - new amount 
	 * @param price - new price
	 */
	public void UpdatePendingOrder(PendingOrder po, int amount, double price) {
		Transaction tx = session.beginTransaction();
		po.setAmount(amount);
		po.setPrice(price);
		session.update(po);
		tx.commit();
	}
	
	/**
	 * Cancel the specified pending order
	 * 
	 * @param po - the specified pending order
	 */
	public void CancelPendingOrder(PendingOrder po) {
		Transaction tx = session.beginTransaction();
		session.delete(po);
		tx.commit();
	}
	
	/**
	 * Cancel all pending orders of the specified product
	 * 
	 * @param product - the specified product (e.g. EURUSD)
	 */
	public void CancelAllPendingOrders(String product) {
		Transaction tx = session.beginTransaction();
		Query q = session.createQuery("delete from PendingOrder where product = :product");
		q.setParameter("product", product);
		q.executeUpdate();
		tx.commit();
	}
	
	/**
	 * When new market data comes in, check for all pending orders to see if any actions need to be taken
	 * 
	 * @param product - the specified product (e.g. EURUSD)
	 * @param bid - the bid {@link MarketData}
	 * @param ask - the ask {@link MarketData}
	 */
	public void Update(String product, MarketData bid, MarketData ask) {
		Transaction tx = session.beginTransaction();
		Query q = session.createQuery("from PendingOrder where product = :product and account.id = :id");
		q.setParameter("id", account.getId());
		q.setParameter("product", product);
		List<PendingOrder> list = q.list();
		tx.commit();
	
		try {
			for(int k = 0; k < list.size(); k++) {
				PendingOrder po = list.get(k);
				
				if(po.getAmount() > 0 && po.getType() == "limit") { // buy limit
					if(ask.getLow() <= po.getPrice()) {
						double price = Math.min(ask.getHigh(), po.getPrice());
						this.MarketBuy(po.getProduct(), ask.getStart(), price, po.getAmount());
						this.CancelPendingOrder(po);
						//System.out.println("buy limit turns to market buy");
					}
				}
				else if(po.getAmount() > 0 && po.getType() == "stop") { //buy stop
					if(ask.getHigh() >= po.getPrice()) {
						double price = Math.max(ask.getLow(), po.getPrice());
						this.MarketBuy(po.getProduct(), ask.getStart(), price, po.getAmount());
						this.CancelPendingOrder(po);
						//System.out.println("buy stop turns to market buy");
					}	
				}
				else if(po.getAmount() < 0 && po.getType() == "limit") { //sell limit
					if(bid.getHigh() >= po.getPrice()) {
						double price = Math.max(bid.getLow(), po.getPrice());
						this.MarketSell(po.getProduct(), bid.getStart(), price, po.getAmount() * -1);
						this.CancelPendingOrder(po);
						//System.out.println("sell limit turns to market sell");
					}
				}
				else if(po.getAmount() < 0 && po.getType() == "stop") { // sell stop
					if(bid.getLow() <= po.getPrice()) {
						double price = Math.min(bid.getHigh(), po.getPrice());
						this.MarketSell(po.getProduct(), bid.getStart(), price, po.getAmount() * -1);
						this.CancelPendingOrder(po);
						//System.out.println("sell stop turns to market sell");
					}
				}
			}
			
		}
		catch(Exception ex) {
			System.out.println(ex.getMessage());
		}
		
	}
}
