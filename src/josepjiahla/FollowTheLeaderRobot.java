package josepjiahla;

import robocode.*;
import robocode.util.Utils;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.*;

public class FollowTheLeaderRobot extends TeamRobot {
    // Variables per a la jerarquia i lideratge
    private boolean isLeader = false;
    private String leaderName = null;
    private List<String> teamMembers = new ArrayList<>();
    private Map<String, Point2D.Double> robotPositions = new HashMap<>();
    private Map<String, String> hierarchy = new LinkedHashMap<>();
    private Set<String> aliveTeamMembers = new HashSet<>();

    // Variables per al moviment
    private List<Point2D.Double> corners = new ArrayList<>();
    private int currentCornerIndex = -1;
    private boolean clockwise = true; // Assegurem que comença en sentit horari
    private long lastRoleChangeTime = 0;

    // Variables per a l'enemic
    private Map<String, EnemyInfo> enemies = new HashMap<>(); // Llista d'enemics detectats
    private EnemyInfo targetEnemy = null; // Enemic objectiu actual
    private long enemyLastSeenTime = 0;

    // Variables per a l'establiment de la jerarquia
    private Map<String, Double> distancesFromRobots = new HashMap<>();
    private int expectedDistanceMessages = 0;

    // Constants per al control del foc
    private static final double MAX_FIRE_POWER = 3.0;
    private static final double MIN_FIRE_POWER = 1.0;

    public void run() {
        // Configuració inicial (eliminat el canvi de color)
        
        // Permetre que el canó i el radar es moguin independentment del cos
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        // Definim les cantonades del camp de batalla
        defineBattlefieldCorners();

        // FASE 0: Handshake i elecció del líder
        performHandshake();

        // Inicialitzem el temps de l'últim canvi de rol
        lastRoleChangeTime = getTime();

        // Esperem fins que els rols estiguin assignats
        while (!hierarchy.containsKey(getName()) && !isLeader) {
            execute();
        }

        // Inicialitzar la llista de membres vius
        for (String member : teamMembers) {
            aliveTeamMembers.add(member.split("#")[0]);
        }

        // Bucle principal
        long lastPositionUpdateTime = 0;

        while (true) {
            // Cada 15 segons, canvi de rols i inversió de direcció
            if (getTime() - lastRoleChangeTime >= 15 * 20) { // 15 segons * 20 ticks per segon
                rotateRoles();
                lastRoleChangeTime = getTime();
            }

            if (isLeader) {
                // Moviment del líder
                moveLeader();
                // Seleccionar enemic objectiu
                selectTargetEnemy();
                // Enviar enemic objectiu a l'equip
                broadcastTargetEnemy();
            } else {
                // Seguir l'antecessor
                followPredecessor();
            }

            // Control del radar
            radarControl();

            // Apuntar i disparar a l'enemic
            if (targetEnemy != null && (getTime() - enemyLastSeenTime) < 8) { // Si hem vist l'enemic en els darrers 8 ticks
                trackAndFire();
            }

            // Enviar la posició actual als altres robots cada 5 ticks
            if (getTime() - lastPositionUpdateTime >= 5) {
                try {
                    broadcastMessage(new PositionUpdate(getName(), getX(), getY()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                lastPositionUpdateTime = getTime();
            }

            execute();
        }
    }

    private void defineBattlefieldCorners() {
        double marginX = getBattleFieldWidth() * 0.1;
        double marginY = getBattleFieldHeight() * 0.1;

        corners.add(new Point2D.Double(marginX, marginY));
        corners.add(new Point2D.Double(getBattleFieldWidth() - marginX, marginY));
        corners.add(new Point2D.Double(getBattleFieldWidth() - marginX, getBattleFieldHeight() - marginY));
        corners.add(new Point2D.Double(marginX, getBattleFieldHeight() - marginY));
    }

    private void performHandshake() {
        // Enviem el nostre número aleatori als altres
        int myRandomNumber = (int) (Math.random() * 1000);
        try {
            broadcastMessage(new LeaderProposal(getName(), myRandomNumber));
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Afegim el nostre robot a la llista de membres de l'equip
        teamMembers.add(getName() + "#" + myRandomNumber);

        // Esperem a rebre propostes d'altres robots durant un temps curt
        long waitTime = getTime() + 5; // Esperem 0.25 segons (5 ticks)
        while (getTime() < waitTime) {
            execute();
        }

        // Seleccionem el líder
        selectLeader();

        // Establim la jerarquia per distàncies
        establishHierarchy();
    }

    private void selectLeader() {
        int highestNumber = -1;

        for (String member : teamMembers) {
            int number = getRandomNumberFromName(member);
            if (number > highestNumber) {
                highestNumber = number;
                leaderName = member.split("#")[0];
            }
        }

        isLeader = leaderName.equals(getName());
    }

    private int getRandomNumberFromName(String name) {
        String[] parts = name.split("#");
        if (parts.length > 1) {
            return Integer.parseInt(parts[1]);
        } else {
            return 0;
        }
    }

    private void establishHierarchy() {
        if (isLeader) {
            // El líder envia la seva posició perquè els altres calculin la distància
            try {
                broadcastMessage(new LeaderAnnouncement(getName(), getX(), getY()));
            } catch (IOException e) {
                e.printStackTrace();
            }

            distancesFromRobots = new HashMap<>();
            expectedDistanceMessages = teamMembers.size() - 1;

            // Esperem fins a rebre totes les distàncies
            while (distancesFromRobots.size() < expectedDistanceMessages) {
                execute();
            }

            // Construïm la jerarquia
            buildHierarchy();

            // Enviem la jerarquia als robots
            try {
                broadcastMessage(new HierarchyUpdate(hierarchy));
            } catch (IOException e) {
                e.printStackTrace();
            }

        } else {
            // Esperem a rebre la posició del líder
            while (!robotPositions.containsKey(leaderName)) {
                execute();
            }
            // Rebem la posició del líder i calculem la nostra distància
            double distanceToLeader = Point2D.distance(getX(), getY(), robotPositions.get(leaderName).getX(), robotPositions.get(leaderName).getY());
            try {
                sendMessage(leaderName, new DistanceMessage(getName(), distanceToLeader));
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Esperem a rebre la jerarquia
            while (!hierarchy.containsKey(getName())) {
                execute();
            }
        }
    }

    private void buildHierarchy() {
        // Ordenem els robots per distància
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(distancesFromRobots.entrySet());
        Collections.sort(sortedEntries, Comparator.comparingDouble(Map.Entry::getValue));

        hierarchy = new LinkedHashMap<>();
        String previousRobot = leaderName;
        for (Map.Entry<String, Double> entry : sortedEntries) {
            String robotName = entry.getKey();
            hierarchy.put(robotName, previousRobot);
            previousRobot = robotName;
        }
    }

    private void moveLeader() {
        if (currentCornerIndex == -1) {
            // Calculem la cantonada més propera
            currentCornerIndex = getClosestCornerIndex();
        }

        Point2D.Double targetCorner = corners.get(currentCornerIndex);

        // Movem cap a la cantonada objectiu
        goTo(targetCorner.getX(), targetCorner.getY());

        // Si arribem a la cantonada, anem a la següent
        if (getDistanceTo(targetCorner) < 20) {
            if (clockwise) {
                currentCornerIndex = (currentCornerIndex - 1 + corners.size()) % corners.size();
            } else {
                currentCornerIndex = (currentCornerIndex + 1) % corners.size();
            }
        }
    }

    private int getClosestCornerIndex() {
        double minDistance = Double.MAX_VALUE;
        int index = 0;
        for (int i = 0; i < corners.size(); i++) {
            double distance = getDistanceTo(corners.get(i));
            if (distance < minDistance) {
                minDistance = distance;
                index = i;
            }
        }
        return index;
    }

    private double getDistanceTo(Point2D.Double point) {
        return Point2D.distance(getX(), getY(), point.getX(), point.getY());
    }

    private void followPredecessor() {
        String predecessor = getAlivePredecessor(getName());
        if (predecessor != null) {
            Point2D.Double predecessorPosition = robotPositions.get(predecessor);
            if (predecessorPosition != null) {
                // Mantenir una distància constant amb el predecessor
                double distance = getDistanceTo(predecessorPosition);
                if (distance > 100) { // Si està massa lluny, apropa't més
                    goTo(predecessorPosition.getX(), predecessorPosition.getY());
                } else if (distance < 50) { // Si està massa a prop, retrocedeix una mica
                    back(50);
                }
            }
        }
    }

    private String getAlivePredecessor(String robotName) {
        String predecessor = hierarchy.get(robotName);
        while (predecessor != null && !aliveTeamMembers.contains(predecessor)) {
            predecessor = hierarchy.get(predecessor);
        }
        if (predecessor == null && !robotName.equals(leaderName)) {
            // Si no hi ha predecessor viu, seguim al líder
            predecessor = leaderName;
        }
        return predecessor;
    }

    private void rotateRoles() {
        // Invertim la direcció de rotació
        clockwise = !clockwise;

        // Canvi de rols
        List<String> members = new ArrayList<>(hierarchy.keySet());
        members.add(0, leaderName);

        // L'últim robot passa a ser el nou líder
        String newLeader = null;
        for (int i = members.size() - 1; i >= 0; i--) {
            String candidate = members.get(i);
            if (aliveTeamMembers.contains(candidate)) {
                newLeader = candidate;
                members.remove(i);
                break;
            }
        }

        if (newLeader != null) {
            leaderName = newLeader;
            isLeader = leaderName.equals(getName());

            // Actualitzem la jerarquia
            hierarchy.clear();
            String previousRobot = leaderName;
            for (String member : members) {
                if (aliveTeamMembers.contains(member)) {
                    hierarchy.put(member, previousRobot);
                    previousRobot = member;
                }
            }

            // Enviar el nou líder i la jerarquia actualitzada a tots els robots
            try {
                broadcastMessage(new LeaderAnnouncement(leaderName, getX(), getY()));
                broadcastMessage(new HierarchyUpdate(hierarchy));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void goTo(double x, double y) {
        double dx = x - getX();
        double dy = y - getY();

        double angleToTarget = Math.toDegrees(Math.atan2(dx, dy));
        double targetAngle = Utils.normalRelativeAngleDegrees(angleToTarget - getHeading());

        setTurnRight(targetAngle);
        setAhead(Math.hypot(dx, dy));
    }

    private void radarControl() {
        // Control del radar per mantenir l'enemic en el radar
        if (targetEnemy != null) {
            double absoluteBearing = Math.toDegrees(Math.atan2(targetEnemy.getX() - getX(), targetEnemy.getY() - getY()));
            double radarTurn = Utils.normalRelativeAngleDegrees(absoluteBearing - getRadarHeading());
            setTurnRadarRight(radarTurn * 2); // Multipliquem per 2 per assegurar-nos que el radar segueix l'enemic
        } else {
            // Si no tenim enemic, escanegem 360 graus
            setTurnRadarRight(360);
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        if (isTeammate(e.getName())) {
            return;
        }

        // Calcular les coordenades de l'enemic
        double absoluteBearing = Math.toRadians(getHeading() + e.getBearing());
        double enemyX = getX() + Math.sin(absoluteBearing) * e.getDistance();
        double enemyY = getY() + Math.cos(absoluteBearing) * e.getDistance();

        // Crear objecte EnemyInfo
        EnemyInfo enemyInfo = new EnemyInfo(
                e.getName(),
                e.getBearing(),
                e.getDistance(),
                e.getHeading(),
                e.getVelocity(),
                enemyX,
                enemyY,
                getTime()
        );

        // Afegir o actualitzar l'enemic a la llista
        enemies.put(e.getName(), enemyInfo);

        // Enviar la informació de l'enemic al líder
        try {
            sendMessage(leaderName, enemyInfo);
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        // Si no som el líder i no tenim un enemic objectiu, esperem que el líder ens digui quin és
        if (!isLeader && targetEnemy == null) {
            return;
        }

        // Si l'enemic escanejat és el nostre objectiu, actualitzem la seva informació
        if (targetEnemy != null && e.getName().equals(targetEnemy.getEnemyName())) {
            targetEnemy = enemyInfo;
            enemyLastSeenTime = getTime();
        }
    }

    private void selectTargetEnemy() {
        // Si no tenim enemics detectats, no podem seleccionar cap objectiu
        if (enemies.isEmpty()) {
            targetEnemy = null;
            return;
        }

        // Seleccionem l'enemic més proper
        EnemyInfo closestEnemy = null;
        double minDistance = Double.MAX_VALUE;

        for (EnemyInfo enemy : enemies.values()) {
            if (enemy.getDistance() < minDistance) {
                minDistance = enemy.getDistance();
                closestEnemy = enemy;
            }
        }

        targetEnemy = closestEnemy;
        enemyLastSeenTime = getTime();
    }

    private void broadcastTargetEnemy() {
        if (targetEnemy != null) {
            // Enviar l'enemic objectiu a tot l'equip
            try {
                broadcastMessage(new TargetEnemyMessage(targetEnemy));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void trackAndFire() {
        // Predicció de la posició futura de l'enemic
        double bulletPower = getOptimalFirePower(targetEnemy.getDistance());
        double bulletSpeed = Rules.getBulletSpeed(bulletPower);
        double timeToHit = targetEnemy.getDistance() / bulletSpeed;

        double futureX = targetEnemy.getX() + Math.sin(Math.toRadians(targetEnemy.getHeading())) * targetEnemy.getVelocity() * timeToHit;
        double futureY = targetEnemy.getY() + Math.cos(Math.toRadians(targetEnemy.getHeading())) * targetEnemy.getVelocity() * timeToHit;

        // Ajustar si la posició prevista està fora del camp de batalla
        futureX = Math.max(Math.min(futureX, getBattleFieldWidth() - 18), 18);
        futureY = Math.max(Math.min(futureY, getBattleFieldHeight() - 18), 18);

        // Calcular l'angle cap a la posició prevista
        double angleToEnemy = Math.toDegrees(Math.atan2(futureX - getX(), futureY - getY()));
        double gunTurn = Utils.normalRelativeAngleDegrees(angleToEnemy - getGunHeading());

        setTurnGunRight(gunTurn);

        // Disparar si el canó està alineat
        if (Math.abs(gunTurn) < 10) {
            setFire(bulletPower);
        }
    }

    private double getOptimalFirePower(double distance) {
        if (distance < 200) {
            return MAX_FIRE_POWER;
        } else if (distance < 400) {
            return 2.5;
        } else {
            return MIN_FIRE_POWER;
        }
    }

    public void onHitRobot(HitRobotEvent e) {
        if (!isTeammate(e.getName())) {
            setFire(2);
            back(50);
        } else {
            back(20);
        }
    }

    public void onMessageReceived(MessageEvent e) {
        Object message = e.getMessage();
        if (message instanceof LeaderProposal) {
            LeaderProposal proposal = (LeaderProposal) message;
            String memberName = proposal.getRobotName() + "#" + proposal.getRandomNumber();
            if (!teamMembers.contains(memberName)) {
                teamMembers.add(memberName);
                aliveTeamMembers.add(proposal.getRobotName());
            }
        } else if (message instanceof LeaderAnnouncement) {
            LeaderAnnouncement leaderMessage = (LeaderAnnouncement) message;
            leaderName = leaderMessage.getRobotName();
            isLeader = leaderName.equals(getName()); // Actualitzem isLeader
            robotPositions.put(leaderName, new Point2D.Double(leaderMessage.getX(), leaderMessage.getY()));
        } else if (message instanceof PositionUpdate) {
            PositionUpdate positionUpdate = (PositionUpdate) message;
            robotPositions.put(positionUpdate.getRobotName(), new Point2D.Double(positionUpdate.getX(), positionUpdate.getY()));
        } else if (message instanceof EnemyInfo) {
            EnemyInfo enemyInfo = (EnemyInfo) message;
            // Actualitzar informació de l'enemic
            enemies.put(enemyInfo.getEnemyName(), enemyInfo);
        } else if (message instanceof TargetEnemyMessage) {
            TargetEnemyMessage tem = (TargetEnemyMessage) message;
            targetEnemy = tem.getEnemyInfo();
            enemyLastSeenTime = getTime();
        } else if (message instanceof DistanceMessage && isLeader) {
            DistanceMessage dm = (DistanceMessage) message;
            distancesFromRobots.put(dm.getRobotName(), dm.getDistance());
        } else if (message instanceof HierarchyUpdate) {
            HierarchyUpdate hu = (HierarchyUpdate) message;
            hierarchy = hu.getHierarchy();
        }
    }

    public void onRobotDeath(RobotDeathEvent event) {
        String deadRobot = event.getName();
        if (isTeammate(deadRobot)) {
            aliveTeamMembers.remove(deadRobot);

            if (deadRobot.equals(leaderName)) {
                if (aliveTeamMembers.contains(getName()) && hierarchy.get(getName()) == null) {
                    isLeader = true;
                    leaderName = getName();
                    hierarchy.remove(getName());

                    try {
                        broadcastMessage(new LeaderAnnouncement(leaderName, getX(), getY()));
                        broadcastMessage(new HierarchyUpdate(hierarchy));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                } else {
                    leaderName = getAlivePredecessor(deadRobot);
                }
            }

            if (hierarchy.containsKey(deadRobot)) {
                hierarchy.remove(deadRobot);
            }

            for (Map.Entry<String, String> entry : hierarchy.entrySet()) {
                String robotName = entry.getKey();
                String predecessor = entry.getValue();

                if (predecessor.equals(deadRobot)) {
                    String newPredecessor = getAlivePredecessor(robotName);
                    hierarchy.put(robotName, newPredecessor);
                }
            }
        } else {
            enemies.remove(deadRobot);
            if (targetEnemy != null && targetEnemy.getEnemyName().equals(deadRobot)) {
                targetEnemy = null;
            }
        }
    }

    public void onPaint(Graphics2D g) {
        if (isLeader) {
            g.setColor(java.awt.Color.yellow);
            int radius = 50;  // Incrementem la mida del cercle a 50
            int diameter = radius * 2;
            int x = (int) (getX() - radius);
            int y = (int) (getY() - radius);

            g.drawOval(x, y, diameter, diameter);
        }
    }

    // Classes per a la comunicació de missatges entre els robots
    static class LeaderProposal implements java.io.Serializable {
        private String robotName;
        private int randomNumber;

        public LeaderProposal(String robotName, int randomNumber) {
            this.robotName = robotName;
            this.randomNumber = randomNumber;
        }

        public String getRobotName() {
            return robotName;
        }

        public int getRandomNumber() {
            return randomNumber;
        }
    }

    static class LeaderAnnouncement implements java.io.Serializable {
        private String robotName;
        private double x, y;

        public LeaderAnnouncement(String robotName, double x, double y) {
            this.robotName = robotName;
            this.x = x;
            this.y = y;
        }

        public String getRobotName() {
            return robotName;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }
    }

    static class PositionUpdate implements java.io.Serializable {
        private String robotName;
        private double x, y;

        public PositionUpdate(String robotName, double x, double y) {
            this.robotName = robotName;
            this.x = x;
            this.y = y;
        }

        public String getRobotName() {
            return robotName;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }
    }

    static class DistanceMessage implements java.io.Serializable {
        private String robotName;
        private double distance;

        public DistanceMessage(String robotName, double distance) {
            this.robotName = robotName;
            this.distance = distance;
        }

        public String getRobotName() {
            return robotName;
        }

        public double getDistance() {
            return distance;
        }
    }

    static class EnemyInfo implements java.io.Serializable {
        private String enemyName;
        private double bearing;
        private double distance;
        private double heading;
        private double velocity;
        private double x, y;
        private long time;

        public EnemyInfo(String enemyName, double bearing, double distance, double heading, double velocity, double x, double y, long time) {
            this.enemyName = enemyName;
            this.bearing = bearing;
            this.distance = distance;
            this.heading = heading;
            this.velocity = velocity;
            this.x = x;
            this.y = y;
            this.time = time;
        }

        public String getEnemyName() {
            return enemyName;
        }

        public double getBearing() {
            return bearing;
        }

        public double getDistance() {
            return distance;
        }

        public double getHeading() {
            return heading;
        }

        public double getVelocity() {
            return velocity;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public long getTime() {
            return time;
        }
    }

    static class TargetEnemyMessage implements java.io.Serializable {
        private EnemyInfo enemyInfo;

        public TargetEnemyMessage(EnemyInfo enemyInfo) {
            this.enemyInfo = enemyInfo;
        }

        public EnemyInfo getEnemyInfo() {
            return enemyInfo;
        }
    }

    static class HierarchyUpdate implements java.io.Serializable {
        private Map<String, String> hierarchy;

        public HierarchyUpdate(Map<String, String> hierarchy) {
            this.hierarchy = hierarchy;
        }

        public Map<String, String> getHierarchy() {
            return hierarchy;
        }
    }
}
