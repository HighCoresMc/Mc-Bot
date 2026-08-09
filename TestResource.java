public class TestResource {
    public static void main(String[] args) {
        System.out.println("CL: " + TestResource.class.getClassLoader().getResource("Identity/Drop.png"));
        System.out.println("Class: " + TestResource.class.getResource("/Identity/Drop.png"));
    }
}
