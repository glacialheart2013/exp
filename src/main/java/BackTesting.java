import data.*;
import strategy.*;
import model.*;
import org.hibernate.*;
import org.hibernate.cfg.*;
import java.util.*;
import order.*;

public class BackTesting {

	public static void main(String[] args) {
		Session session = HibernateUtil.getSessionFactory().openSession();
		Transaction tx = session.beginTransaction();
		Account account = (Account) session.get(Account.class, 1);
		tx.commit();
		cleanUp(session, account);


		Order order = new BtOrder(session, account);
		TurtleStrategy ts = new TurtleStrategy(order);
		
		MarketDataPusher mdp = new MarketDataPusher("EURUSD", 15, "2012-01-01", "2012-12-31");
		int barNum = mdp.getBarNum();
		mdp.Attach(order);
		mdp.Attach(ts);
		int i = 0;
		while(mdp.Notify()) {
			i++;
			System.out.println(String.format("%d/%d", i, barNum));
		}
		
		session.close();
		HibernateUtil.shutdown();
	}
	
	public static void cleanUp(Session session, Account account) {
		Transaction tx = session.beginTransaction();
		Query q = session.createQuery("delete from Position where account.id = :id");
		q.setParameter("id", account.getId());
		q.executeUpdate();
		q = session.createQuery("delete from TransactionHistory where account.id = :id");
		q.setParameter("id", account.getId());
		q.executeUpdate();
		q = session.createQuery("delete from PendingOrder where account.id = :id");
		q.setParameter("id", account.getId());
		q.executeUpdate();
		tx.commit();
	}
}
