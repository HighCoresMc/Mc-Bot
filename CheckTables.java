
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

public class CheckTables {
    public static void main(String[] args) throws Exception {
        Connection c = DriverManager.getConnection("jdbc:mysql://localhost:3306/minecraft?useSSL=false", "root", "");
        ResultSet rs = c.getMetaData().getTables(null,null,"%",new String[]{"TABLE"});
        while(rs.next()) System.out.println(rs.getString("TABLE_NAME"));
    }
}

