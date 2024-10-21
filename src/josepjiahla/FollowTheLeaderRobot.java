package josepjiahla;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.*;

/**
 * Classe FollowTheLeaderRobot. Aquesta classe representa un robot d'equip que segueix una
 * jerarquia de comandament i que es mou en formacio seguint el lider. El robot pot alternar
 * els seus rols entre comandant i seguidor depenent de les condicions de la batalla.
 */
public class FollowTheLeaderRobot extends TeamRobot {

    // Variables generals
    private boolean isCommander = false;
    private String currentCommander = null;
    private List<String> teamMembers = new ArrayList<>();
    private Map<String, Point2D.Double> robotLocations = new HashMap<>();
    private Map<String, String> teamHierarchy = new LinkedHashMap<>();
    private Set<String> activeMembers = new HashSet<>();
    private List<Point2D.Double> battlefieldCorners = new ArrayList<>();
    private int targetCornerIndex = -1;
    private boolean clockwise = true;
    private long lastRoleSwitchTime = 0;
    private Map<String, EnemyData> detectedEnemies = new HashMap<>();
    private EnemyData primaryTarget = null;
    private long lastEnemySeenTime = 0;
    private Map<String, Double> distancesFromCommander = new HashMap<>();
    private int expectedDistanceMessages = 0;
    private boolean esquivando = false;

    // Constants
    private static final double MAX_FIRE_POWER = 3.0;
    private static final double MIN_FIRE_POWER = 1.0;
    private static final long ROLE_SWITCH_INTERVAL = 300;
    private static final double FOLLOW_DISTANCE = 100;
    private static final double RETRAER_DISTANCIA = 50;
    private static final long POSITION_BROADCAST_INTERVAL = 5;
    private static final long RADAR_SWEEP_INTERVAL = 40;
    private static final double SAFETY_MARGIN = 0.10;
    private static final double DISTANCE_TOLERANCE = 5.0;

    // Variables de moviment
    private Point2D.Double destination = null;
    private boolean isMoving = false;

    /**
     * Metode principal que s'executa de manera continuada durant la batalla.
     * Implementa el comportament del robot segons el seu rol actual.
     */
    @Override
    public void run() {
        setupRobot();
        initiateHandshake();
        lastRoleSwitchTime = getTime();

        for (String member : teamMembers) {
            activeMembers.add(member.split("#")[0]);
        }

        long lastPositionBroadcast = 0;

        while (true) {
            long currentTime = getTime();

            // Rotacio de rols periodica
            if (currentTime - lastRoleSwitchTime >= ROLE_SWITCH_INTERVAL) {
                switchRoles();
                lastRoleSwitchTime = currentTime;
            }

            // Comportament d'esquivar
            if (esquivando) {
                esquivarObstaculo();
            } else {
                // Comportament normal
                if (isCommander) {
                    navigateCommander();
                    choosePrimaryTarget();
                    broadcastPrimaryTarget();
                } else {
                    followPredecessor();
                }
            }

            manageRadar();

            // Atacar l'enemic si es visible
            if (primaryTarget != null && (currentTime - lastEnemySeenTime) < RADAR_SWEEP_INTERVAL) {
                trackAndFire();
            }

            // Actualitzacio periodica de posicions
            if (currentTime - lastPositionBroadcast >= POSITION_BROADCAST_INTERVAL) {
                broadcastLocation();
                lastPositionBroadcast = currentTime;
            }

            execute();
        }
    }

    /**
     * Configura els colors del robot i ajusta la seva posicio.
     */
    private void setupRobot() {
        setColors(Color.BLACK, Color.GREEN, Color.BLACK);
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);
        defineBattlefieldCorners();
    }

    /**
     * Defineix els marges de seguretat del camp de batalla, creant una llista de les cantonades.
     */
    private void defineBattlefieldCorners() {
        double marginX = getBattleFieldWidth() * SAFETY_MARGIN;
        double marginY = getBattleFieldHeight() * SAFETY_MARGIN;

        battlefieldCorners.clear();
        battlefieldCorners.add(new Point2D.Double(marginX, marginY));
        battlefieldCorners.add(new Point2D.Double(getBattleFieldWidth() - marginX, marginY));
        battlefieldCorners.add(new Point2D.Double(getBattleFieldWidth() - marginX, getBattleFieldHeight() - marginY));
        battlefieldCorners.add(new Point2D.Double(marginX, getBattleFieldHeight() - marginY));
    }

    /**
     * Inicia el protocol per establir la jerarquia d'equip, seleccionant el comandant.
     */
    private void initiateHandshake() {
        int randomNumber = (int) (Math.random() * 1000);
        try {
            broadcastMessage(new LeaderProposal(getName(), randomNumber));
        } catch (IOException e) {
            logError("Failed to send LeaderProposal", e);
        }

        teamMembers.add(getName() + "#" + randomNumber);

        long waitUntil = getTime() + 5;
        while (getTime() < waitUntil) {
            execute();
        }

        selectCommander();
        establishHierarchy();
    }

    /**
     * Selecciona el comandant en funcio del numero aleatori assignat a cada membre de l'equip.
     */
    private void selectCommander() {
        int highestNumber = -1;
        String potentialCommander = null;

        for (String member : teamMembers) {
            int number = extractRandomNumber(member);
            if (number > highestNumber) {
                highestNumber = number;
                potentialCommander = member.split("#")[0];
            }
        }

        if (potentialCommander != null) {
            currentCommander = potentialCommander;
            isCommander = currentCommander.equals(getName());
        }
    }

    /**
     * Extreu el numero aleatori assignat a un membre de l'equip.
     *
     * @param member El membre de l'equip amb un nom i un numero.
     * @return El numero aleatori assignat.
     */
    private int extractRandomNumber(String member) {
        String[] parts = member.split("#");
        try {
            return parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (NumberFormatException e) {
            logError("Invalid number format in team member: " + member, e);
            return 0;
        }
    }

    /**
     * Estableix la jerarquia de l'equip basada en la distancia al comandant.
     */
    private void establishHierarchy() {
        if (isCommander) {
            announceCommander();

            distancesFromCommander.clear();
            expectedDistanceMessages = teamMembers.size() - 1;

            long waitTime = getTime() + 40;
            while (distancesFromCommander.size() < expectedDistanceMessages && getTime() < waitTime) {
                execute();
            }

            buildHierarchy();
            broadcastHierarchy();
        } else {
            waitForCommanderLocation();
            reportDistanceToCommander();
            waitForHierarchy();
        }
    }

    /**
     * Anuncia que aquest robot es el nou comandant.
     */
    private void announceCommander() {
        try {
            broadcastMessage(new CommanderAnnouncement(getName(), getX(), getY()));
        } catch (IOException e) {
            logError("Failed to send CommanderAnnouncement", e);
        }
    }

    /**
     * Espera fins que es rebi la localitzacio del comandant.
     */
    private void waitForCommanderLocation() {
        while (!robotLocations.containsKey(currentCommander)) {
            execute();
        }
    }

    /**
     * Envia la distancia actual al comandant.
     */
    private void reportDistanceToCommander() {
        Point2D.Double commanderPos = robotLocations.get(currentCommander);
        double distance = Point2D.distance(getX(), getY(), commanderPos.getX(), commanderPos.getY());
        try {
            sendMessage(currentCommander, new DistanceReport(getName(), distance));
        } catch (IOException e) {
            logError("Failed to send DistanceReport to commander", e);
        }
    }

    /**
     * Espera la jerarquia de l'equip establerta pel comandant.
     */
    private void waitForHierarchy() {
        while (!teamHierarchy.containsKey(getName())) {
            execute();
        }
    }

    /**
     * Construeix la jerarquia d'acord amb les distancies dels membres respecte al comandant.
     */
    private void buildHierarchy() {
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(distancesFromCommander.entrySet());
        sortedEntries.sort(Comparator.comparingDouble(Map.Entry::getValue));

        teamHierarchy.clear();
        String previous = currentCommander;

        for (Map.Entry<String, Double> entry : sortedEntries) {
            String robot = entry.getKey();
            teamHierarchy.put(robot, previous);
            previous = robot;
        }
    }

    /**
     * Envia la jerarquia de l'equip a tots els membres.
     */
    private void broadcastHierarchy() {
        try {
            broadcastMessage(new HierarchyUpdate(teamHierarchy));
        } catch (IOException e) {
            logError("Failed to send HierarchyUpdate", e);
        }
    }

    /**
     * Canvia el rol dels robots de manera periodica i selecciona un nou comandant si es necessari.
     */
    private void switchRoles() {
        clockwise = !clockwise;
        List<String> membersList = new ArrayList<>(teamHierarchy.keySet());
        membersList.add(0, currentCommander);

        String newCommander = null;
        for (int i = membersList.size() - 1; i >= 0; i--) {
            String candidate = membersList.get(i);
            if (activeMembers.contains(candidate)) {
                newCommander = candidate;
                break;
            }
        }

        if (newCommander != null) {
            currentCommander = newCommander;
            isCommander = currentCommander.equals(getName());
            rebuildHierarchy(membersList);
            announceNewCommander();
        }
    }

    /**
     * Reconstrueix la jerarquia d'acord amb els membres vius de l'equip.
     */
    private void rebuildHierarchy(List<String> members) {
        teamHierarchy.clear();
        String previous = currentCommander;

        for (String member : members) {
            if (activeMembers.contains(member)) {
                teamHierarchy.put(member, previous);
                previous = member;
            }
        }
    }

    /**
     * Anuncia el nou comandant a tots els membres de l'equip.
     */
    private void announceNewCommander() {
        try {
            broadcastMessage(new CommanderAnnouncement(currentCommander, getX(), getY()));
            broadcastMessage(new HierarchyUpdate(teamHierarchy));
        } catch (IOException e) {
            logError("Failed to send new CommanderAnnouncement and HierarchyUpdate", e);
        }
    }

    /**
     * Navega el comandant cap a una cantonada del camp de batalla.
     */
    private void navigateCommander() {
        if (!isMoving) {
            targetCornerIndex = findNearestCorner();
            destination = battlefieldCorners.get(targetCornerIndex);
            isMoving = true;
        }

        if (getDistance(destination) < DISTANCE_TOLERANCE) {
            targetCornerIndex = (clockwise) ? (targetCornerIndex + 1) % battlefieldCorners.size()
                                           : (targetCornerIndex - 1 + battlefieldCorners.size()) % battlefieldCorners.size();
            destination = battlefieldCorners.get(targetCornerIndex);
        }

        moveTo(destination.getX(), destination.getY());
    }

    /**
     * Troba la cantonada mes propera al robot.
     *
     * @return L'index de la cantonada mes propera.
     */
    private int findNearestCorner() {
        double minDistance = Double.MAX_VALUE;
        int closestIndex = 0;
        for (int i = 0; i < battlefieldCorners.size(); i++) {
            double distance = getDistance(battlefieldCorners.get(i));
            if (distance < minDistance) {
                minDistance = distance;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    /**
     * Calcula la distancia entre el robot i un punt especific.
     *
     * @param point El punt amb el qual es vol mesurar la distancia.
     * @return La distancia fins al punt especificat.
     */
    private double getDistance(Point2D.Double point) {
        return Point2D.distance(getX(), getY(), point.getX(), point.getY());
    }

    /**
     * Mou el robot cap a unes coordenades especificades.
     *
     * @param x Coordenada X de la destinacio.
     * @param y Coordenada Y de la destinacio.
     */
    private void moveTo(double x, double y) {
        double deltaX = x - getX();
        double deltaY = y - getY();
        double targetAngle = Math.toDegrees(Math.atan2(deltaX, deltaY));
        double turnAngle = Utils.normalRelativeAngleDegrees(targetAngle - getHeading());
        setTurnRight(turnAngle);
        setAhead(Math.hypot(deltaX, deltaY));
    }

    /**
     * Segueix el robot predecessor dins la jerarquia.
     */
    private void followPredecessor() {
        String predecessor = getAlivePredecessor(getName());
        if (predecessor != null) {
            Point2D.Double predecessorPos = robotLocations.get(predecessor);
            if (predecessorPos != null) {
                double distance = getDistance(predecessorPos);
                if (distance > FOLLOW_DISTANCE) {
                    moveTo(predecessorPos.getX(), predecessorPos.getY());
                } else if (distance < RETRAER_DISTANCIA) {
                    back(RETRAER_DISTANCIA);
                }
            }
        }
    }

    /**
     * Retorna el predecessor viu d'un robot dins la jerarquia.
     *
     * @param robot El nom del robot.
     * @return El predecessor viu o el comandant si no hi ha predecessor.
     */
    private String getAlivePredecessor(String robot) {
        String predecessor = teamHierarchy.get(robot);
        while (predecessor != null && !activeMembers.contains(predecessor)) {
            predecessor = teamHierarchy.get(predecessor);
        }
        return (predecessor == null && !robot.equals(currentCommander)) ? currentCommander : predecessor;
    }

    /**
     * Gestiona el radar per rastrejar l'objectiu primari o escanejar el camp de batalla.
     */
    private void manageRadar() {
        if (primaryTarget != null) {
            double absoluteBearing = Math.toDegrees(Math.atan2(primaryTarget.getX() - getX(), primaryTarget.getY() - getY()));
            double radarTurn = Utils.normalRelativeAngleDegrees(absoluteBearing - getRadarHeading());
            setTurnRadarRight(radarTurn * 2);
        } else {
            setTurnRadarRight(360);
        }
    }

    /**
     * Selecciona l'objectiu enemic principal.
     */
    private void choosePrimaryTarget() {
        if (detectedEnemies.isEmpty()) {
            primaryTarget = null;
            return;
        }

        primaryTarget = detectedEnemies.values().stream()
                .min(Comparator.comparingDouble(EnemyData::getDistance))
                .orElse(null);

        if (primaryTarget != null) {
            lastEnemySeenTime = getTime();
        }
    }

    /**
     * Envia el missatge amb l'objectiu enemic principal a l'equip.
     */
    private void broadcastPrimaryTarget() {
        if (primaryTarget != null) {
            try {
                broadcastMessage(new EnemyTarget(primaryTarget));
            } catch (IOException e) {
                logError("Failed to send EnemyTarget", e);
            }
        }
    }

    /**
     * Rastreja l'objectiu i dispara segons la seva posicio prevista.
     */
    private void trackAndFire() {
        if (primaryTarget == null) return;

        double firePower = determineFirePower(primaryTarget.getDistance());
        double bulletSpeed = Rules.getBulletSpeed(firePower);
        double timeToHit = primaryTarget.getDistance() / bulletSpeed;

        double futureX = primaryTarget.getX() + Math.sin(Math.toRadians(primaryTarget.getDirection())) * primaryTarget.getSpeed() * timeToHit;
        double futureY = primaryTarget.getY() + Math.cos(Math.toRadians(primaryTarget.getDirection())) * primaryTarget.getSpeed() * timeToHit;

        futureX = clamp(futureX, 18, getBattleFieldWidth() - 18);
        futureY = clamp(futureY, 18, getBattleFieldHeight() - 18);

        double targetAngle = Math.toDegrees(Math.atan2(futureX - getX(), futureY - getY()));
        double gunTurn = Utils.normalRelativeAngleDegrees(targetAngle - getGunHeading());

        setTurnGunRight(gunTurn);

        if (Math.abs(gunTurn) < 10) {
            setFire(firePower);
        }
    }

    /**
     * Determina la potència de foc en funcio de la distancia a l'enemic.
     *
     * @param distance La distancia a l'enemic.
     * @return La potència de foc adequada.
     */
    private double determineFirePower(double distance) {
        if (distance < 200) return MAX_FIRE_POWER;
        if (distance < 400) return 2.5;
        return MIN_FIRE_POWER;
    }

    /**
     * Limita un valor dins d'un rang especificat.
     *
     * @param value El valor a limitar.
     * @param min El valor minim.
     * @param max El valor maxim.
     * @return El valor limitat dins del rang.
     */
    private double clamp(double value, double min, double max) {
        return Math.max(Math.min(value, max), min);
    }

    /**
     * Envia la localitzacio actual del robot a l'equip.
     */
    private void broadcastLocation() {
        try {
            broadcastMessage(new PositionUpdate(getName(), getX(), getY()));
        } catch (IOException e) {
            logError("Failed to send PositionUpdate", e);
        }
    }

    /**
     * Gestiona els errors i imprimeix el missatge d'error.
     *
     * @param message El missatge d'error.
     * @param e L'excepcio que s'ha produït.
     */
    private void logError(String message, Exception e) {
        System.err.println(message);
        e.printStackTrace();
    }

    /**
     * Esquiva obstacles o altres robots en la proximitat.
     */
    public void esquivarObstaculo() {
        double x = getX();
        double y = getY();
        double battlefieldWidth = getBattleFieldWidth();
        double battlefieldHeight = getBattleFieldHeight();

        double distanciaNorte = battlefieldHeight - y;
        double distanciaSur = y;
        double distanciaEste = battlefieldWidth - x;
        double distanciaOeste = x;

        if (distanciaNorte > distanciaSur && distanciaEste > distanciaOeste) {
            turnLeft(45);
        } else {
            turnRight(45);
        }

        ahead(75);
        esquivando = false;
    }

    /**
     * Determina si un robot es membre de l'equip.
     *
     * @param robotName El nom del robot a comprovar.
     * @return True si es membre de l'equip, fals en cas contrari.
     */
    private boolean isTeamMember(String robotName) {
        for (String member : teamMembers) {
            if (member.startsWith(robotName + "#")) return true;
        }
        return false;
    }

    /**
     * Gestiona els esdeveniments quan el robot col·lideix amb un altre.
     *
     * @param event L'esdeveniment de col·lisio.
     */
    @Override
    public void onHitRobot(HitRobotEvent event) {
        if (!isTeamMember(event.getName())) {
            setFire(2);
            back(RETRAER_DISTANCIA);
        } else {
            back(20);
        }
    }

    /**
     * Gestiona la mort d'un robot, actualitzant la jerarquia si es necessari.
     *
     * @param event L'esdeveniment de mort del robot.
     */
    @Override
    public void onRobotDeath(RobotDeathEvent event) {
        String deadRobot = event.getName();
        if (isTeamMember(deadRobot)) {
            activeMembers.remove(deadRobot);

            if (deadRobot.equals(currentCommander)) {
                handleCommanderDeath(deadRobot);
            }

            teamHierarchy.remove(deadRobot);
            updateHierarchyAfterDeath(deadRobot);
        } else {
            detectedEnemies.remove(deadRobot);
            if (primaryTarget != null && primaryTarget.getEnemyName().equals(deadRobot)) {
                primaryTarget = null;
            }
        }
    }

    /**
     * Gestiona la mort del comandant, establint un nou comandant si es necessari.
     *
     * @param deadCommander El nom del comandant mort.
     */
    private void handleCommanderDeath(String deadCommander) {
        if (activeMembers.contains(getName()) && teamHierarchy.get(getName()) == null) {
            isCommander = true;
            currentCommander = getName();
            teamHierarchy.remove(getName());

            try {
                broadcastMessage(new CommanderAnnouncement(currentCommander, getX(), getY()));
                broadcastMessage(new HierarchyUpdate(teamHierarchy));
            } catch (IOException e) {
                logError("Failed to announce new commander after commander death", e);
            }
        } else {
            currentCommander = getAlivePredecessor(deadCommander);
        }
    }

    /**
     * Actualitza la jerarquia despres de la mort d'un robot.
     *
     * @param deadRobot El nom del robot mort.
     */
    private void updateHierarchyAfterDeath(String deadRobot) {
        for (Map.Entry<String, String> entry : teamHierarchy.entrySet()) {
            String robot = entry.getKey();
            String predecessor = entry.getValue();

            if (predecessor.equals(deadRobot)) {
                String newPredecessor = getAlivePredecessor(predecessor);
                teamHierarchy.put(robot, newPredecessor);
            }
        }
    }

    /**
     * Dibuixa un cercle al voltant del robot si es el comandant.
     *
     * @param g L'objecte Graphics2D utilitzat per dibuixar.
     */
    @Override
    public void onPaint(Graphics2D g) {
        if (isCommander) {
            g.setColor(Color.YELLOW);
            int radius = 50;
            int diameter = radius * 2;
            int x = (int) (getX() - radius);
            int y = (int) (getY() - radius);
            g.drawOval(x, y, diameter, diameter);
        }
    }

    /**
     * Gestiona els missatges rebuts per part dels altres membres de l'equip.
     *
     * @param event L'esdeveniment de recepcio de missatge.
     */
    @Override
    public void onMessageReceived(MessageEvent event) {
        Object msg = event.getMessage();

        try {
            if (msg instanceof LeaderProposal) {
                handleLeaderProposal((LeaderProposal) msg);
            } else if (msg instanceof CommanderAnnouncement) {
                handleCommanderAnnouncement((CommanderAnnouncement) msg);
            } else if (msg instanceof PositionUpdate) {
                handlePositionUpdate((PositionUpdate) msg);
            } else if (msg instanceof EnemyData) {
                handleEnemyData((EnemyData) msg);
            } else if (msg instanceof EnemyTarget) {
                handleEnemyTarget((EnemyTarget) msg);
            } else if (msg instanceof DistanceReport && isCommander) {
                handleDistanceReport((DistanceReport) msg);
            } else if (msg instanceof HierarchyUpdate) {
                handleHierarchyUpdate((HierarchyUpdate) msg);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Gestiona la proposta de lider rebuda d'un altre robot.
     *
     * @param proposal La proposta de lider.
     */
    private void handleLeaderProposal(LeaderProposal proposal) {
        String name = proposal.getRobotName() + "#" + proposal.getRandomNumber();
        if (!teamMembers.contains(name)) {
            teamMembers.add(name);
            activeMembers.add(proposal.getRobotName());
        }
    }

    /**
     * Gestiona l'anunci d'un nou comandant.
     *
     * @param announcement L'anunci del comandant.
     */
    private void handleCommanderAnnouncement(CommanderAnnouncement announcement) {
        currentCommander = announcement.getCommanderName();
        isCommander = currentCommander.equals(getName());
        robotLocations.put(currentCommander, new Point2D.Double(announcement.getX(), announcement.getY()));
    }

    /**
     * Actualitza la posicio d'un robot segons un missatge de posicio.
     *
     * @param update El missatge d'actualitzacio de posicio.
     */
    private void handlePositionUpdate(PositionUpdate update) {
        robotLocations.put(update.getRobotName(), new Point2D.Double(update.getX(), update.getY()));
    }

    /**
     * Gestiona la informacio d'un enemic detectat.
     *
     * @param enemy La informacio de l'enemic.
     */
    private void handleEnemyData(EnemyData enemy) {
        detectedEnemies.put(enemy.getEnemyName(), enemy);
    }

    /**
     * Gestiona l'objectiu enemic assignat pel comandant.
     *
     * @param targetMsg El missatge amb l'objectiu enemic.
     */
    private void handleEnemyTarget(EnemyTarget targetMsg) {
        primaryTarget = targetMsg.getEnemyInfo();
        lastEnemySeenTime = getTime();
    }

    /**
     * Gestiona el report de distancia enviat per un altre robot.
     *
     * @param report El report de distancia.
     */
    private void handleDistanceReport(DistanceReport report) {
        distancesFromCommander.put(report.getRobotName(), report.getDistance());
    }

    /**
     * Gestiona l'actualitzacio de la jerarquia de l'equip.
     *
     * @param update L'actualitzacio de la jerarquia.
     */
    private void handleHierarchyUpdate(HierarchyUpdate update) {
        teamHierarchy.clear();
        teamHierarchy.putAll(update.getHierarchy());
    }

    // Classes Internes per Missatges

    /**
     * Classe per a enviar propostes de lider a l'equip.
     */
    static class LeaderProposal implements java.io.Serializable {
        private final String robotName;
        private final int randomNumber;

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

    /**
     * Classe per a enviar anuncis de comandant a l'equip.
     */
    static class CommanderAnnouncement implements java.io.Serializable {
        private final String commanderName;
        private final double x, y;

        public CommanderAnnouncement(String commanderName, double x, double y) {
            this.commanderName = commanderName;
            this.x = x;
            this.y = y;
        }

        public String getCommanderName() {
            return commanderName;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }
    }

    /**
     * Classe per a enviar actualitzacions de posicio a l'equip.
     */
    static class PositionUpdate implements java.io.Serializable {
        private final String robotName;
        private final double x, y;

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

    /**
     * Classe per a enviar reportatges de distancia al comandant.
     */
    static class DistanceReport implements java.io.Serializable {
        private final String robotName;
        private final double distance;

        public DistanceReport(String robotName, double distance) {
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

    /**
     * Classe per a emmagatzemar les dades d'un enemic detectat.
     */
    static class EnemyData implements java.io.Serializable {
        private final String enemyName;
        private final double bearing;
        private final double distance;
        private final double direction;
        private final double speed;
        private final double x, y;
        private final long time;
        private final double energy;

        public EnemyData(String enemyName, double bearing, double distance, double direction, double speed, double x, double y, long time, double energy) {
            this.enemyName = enemyName;
            this.bearing = bearing;
            this.distance = distance;
            this.direction = direction;
            this.speed = speed;
            this.x = x;
            this.y = y;
            this.time = time;
            this.energy = energy;
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

        public double getDirection() {
            return direction;
        }

        public double getSpeed() {
            return speed;
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

        public double getEnergy() {
            return energy;
        }
    }

    /**
     * Classe per a enviar objectius enemics a l'equip.
     */
    static class EnemyTarget implements java.io.Serializable {
        private final EnemyData enemyInfo;

        public EnemyTarget(EnemyData enemyInfo) {
            this.enemyInfo = enemyInfo;
        }

        public EnemyData getEnemyInfo() {
            return enemyInfo;
        }
    }

    /**
     * Classe per a enviar actualitzacions de la jerarquia a l'equip.
     */
    static class HierarchyUpdate implements java.io.Serializable {
        private final Map<String, String> hierarchy;

        public HierarchyUpdate(Map<String, String> hierarchy) {
            this.hierarchy = hierarchy;
        }

        public Map<String, String> getHierarchy() {
            return hierarchy;
        }
    }
}
