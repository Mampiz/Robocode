package josepjiahla;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.*;

public class FollowTheLeaderRobot extends TeamRobot {
    private boolean esLider = false;
    private String nombreLider = null;
    private List<String> miembrosEquipo = new ArrayList<>();
    private TreeMap<String, Point2D.Double> posicionesRobots = new TreeMap<>();
    private Map<String, String> jerarquia = new LinkedHashMap<>();
    private Set<String> miembrosVivos = new HashSet<>();
    private List<Point2D.Double> esquinas = new ArrayList<>();
    private int indiceEsquinaActual = -1;
    private boolean sentidoHorario = true;
    private long tiempoUltimoCambioRol = 0;
    private TreeMap<String, EnemyInfo> enemigos = new TreeMap<>();
    private EnemyInfo enemigoObjetivo = null;
    private long tiempoUltimaVezVistoEnemigo = 0;
    private TreeMap<String, Double> distanciasDesdeRobots = new TreeMap<>();
    private int mensajesEsperadosDistancia = 0;

    // Constantes
    private static final double MAX_FIRE_POWER = 3.0;
    private static final double MIN_FIRE_POWER = 1.0;
    private static final long ROLE_SWITCH_INTERVAL = 300; // 15 seconds assuming 20 ticks/sec
    private static final double FOLLOW_DISTANCE = 100;
    private static final double RETRAER_DISTANCIA = 50;
    private static final long POSITION_BROADCAST_INTERVAL = 5;
    private static final long RADAR_SWEEP_INTERVAL = 40;
    private static final double SAFETY_MARGIN = 0.10;
    private static final double DISTANCE_TOLERANCE = 5.0;

    // Variables de movimiento
    private Point2D.Double destination = null;
    private boolean isMoving = false;

    @Override
    public void run() {
        setColors(Color.BLACK, Color.GREEN, Color.GREEN);
        iniciarYRealizarHandshake();
        tiempoUltimoCambioRol = getTime();

        // Solo agregar miembros vivos si la jerarquía está establecida
        while (!jerarquia.containsKey(getName()) && !esLider) {
            execute();
        }

        long lastPositionBroadcast = 0;

        while (true) {
            long currentTime = getTime();

            // Rotació de rols periòdica
            if (currentTime - lastRoleSwitchTime >= ROLE_SWITCH_INTERVAL) {
                switchRoles();
                lastRoleSwitchTime = currentTime;
            }

            // Comportament segons si és el comandant o no
            if (isCommander) {
                navigateCommander();
                choosePrimaryTarget();
                broadcastPrimaryTarget();
            } else {
                followPredecessor();
            }

            manageRadar();

            // Atacar l'enemic si està visible
            if (primaryTarget != null && (currentTime - lastEnemySeenTime) < RADAR_SWEEP_INTERVAL) {
                trackAndFire();
            }

            // Actualització periòdica de posicions
            if (currentTime - lastPositionBroadcast >= POSITION_BROADCAST_INTERVAL) {
                broadcastLocation();
                lastPositionBroadcast = currentTime;
            }

            execute();
        }
    }

    private void definirEsquinasCampoBatalla() {
        double margenX = 100;
        double margenY = 80;

        esquinas.add(new Point2D.Double(margenX, margenY)); // Esquina inferior izquierda
        esquinas.add(new Point2D.Double(1000 - margenX, margenY)); // Esquina inferior derecha
        esquinas.add(new Point2D.Double(1000 - margenX, 800 - margenY)); // Esquina superior derecha
        esquinas.add(new Point2D.Double(margenX, 800 - margenY)); // Esquina superior izquierda
    }

    private void realizarHandshake() {
        int miNumeroAleatorio = (int) (Math.random() * 1000);
        try {
            broadcastMessage(new PropuestaLider(getName(), miNumeroAleatorio));
        } catch (IOException e) {
            e.printStackTrace();
        }

        miembrosEquipo.add(getName() + "#" + miNumeroAleatorio);

        long tiempoEspera = getTime() + 5;
        while (getTime() < tiempoEspera) {
            execute();
        }

        seleccionarLider();
        establecerJerarquia();
    }

    private void seleccionarLider() {
        int numeroMasAlto = -1;

        for (String miembro : miembrosEquipo) {
            int numero = obtenerNumeroAleatorioDeNombre(miembro);
            if (numero > numeroMasAlto) {
                numeroMasAlto = numero;
                nombreLider = miembro.split("#")[0];
            }
        }

        esLider = nombreLider.equals(getName());
    }

    private void iniciarYRealizarHandshake() {
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);
        defineBattlefieldCorners();
    }

    private void defineBattlefieldCorners() {
        double marginX = getBattleFieldWidth() * SAFETY_MARGIN;
        double marginY = getBattleFieldHeight() * SAFETY_MARGIN;

        battlefieldCorners.clear();
        battlefieldCorners.add(new Point2D.Double(marginX, marginY));
        battlefieldCorners.add(new Point2D.Double(getBattleFieldWidth() - marginX, marginY));
        battlefieldCorners.add(new Point2D.Double(getBattleFieldWidth() - marginX, getBattleFieldHeight() - marginY));
        battlefieldCorners.add(new Point2D.Double(marginX, getBattleFieldHeight() - marginY));
    }

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

    private int extractRandomNumber(String member) {
        String[] parts = member.split("#");
        try {
            return parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (NumberFormatException e) {
            logError("Invalid number format in team member: " + member, e);
            return 0;
        }
    }

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

    private void announceCommander() {
        try {
            broadcastMessage(new CommanderAnnouncement(getName(), getX(), getY()));
        } catch (IOException e) {
            logError("Failed to send CommanderAnnouncement", e);
        }
    }

    private void waitForCommanderLocation() {
        while (!robotLocations.containsKey(currentCommander)) {
            execute();
        }
    }

    private void reportDistanceToCommander() {
        Point2D.Double commanderPos = robotLocations.get(currentCommander);
        double distance = Point2D.distance(getX(), getY(), commanderPos.getX(), commanderPos.getY());
        try {
            sendMessage(currentCommander, new DistanceReport(getName(), distance));
        } catch (IOException e) {
            logError("Failed to send DistanceReport to commander", e);
        }
    }

    private void waitForHierarchy() {
        while (!teamHierarchy.containsKey(getName())) {
            execute();
        }
    }

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

    private void broadcastHierarchy() {
        try {
            broadcastMessage(new HierarchyUpdate(teamHierarchy));
        } catch (IOException e) {
            logError("Failed to send HierarchyUpdate", e);
        }
    }

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

    private void moverLider() {
        if (indiceEsquinaActual == -1) {
            indiceEsquinaActual = obtenerIndiceEsquinaMasCercana();
        }

        Point2D.Double esquinaObjetivo = esquinas.get(indiceEsquinaActual);
        irA(esquinaObjetivo.getX(), esquinaObjetivo.getY());

        // Verificar si ya llegó a la esquina objetivo
        if (obtenerDistanciaA(esquinaObjetivo) < 20) {
            indiceEsquinaActual = (indiceEsquinaActual + 1) % esquinas.size();
        }
    }

    private void seguirPredecesor() {
        String predecesor = obtenerPredecesorVivo(getName());
        if (predecesor != null) {
            Point2D.Double posicionPredecesor = posicionesRobots.get(predecesor);
            if (posicionPredecesor != null) {
                double distancia = obtenerDistanciaA(posicionPredecesor);
                if (distancia > 100) {
                    irA(posicionPredecesor.getX(), posicionPredecesor.getY());
                } else if (distancia < 50) {
                    back(50);
                }
            }
        }
    }

    private String getAlivePredecessor(String robot) {
        String predecessor = teamHierarchy.get(robot);
        while (predecessor != null && !activeMembers.contains(predecessor)) {
            predecessor = teamHierarchy.get(predecessor);
        }
        return (predecessor == null && !robot.equals(currentCommander)) ? currentCommander : predecessor;
    }

    private void manageRadar() {
        if (primaryTarget != null) {
            double absoluteBearing = Math.toDegrees(Math.atan2(primaryTarget.getX() - getX(), primaryTarget.getY() - getY()));
            double radarTurn = Utils.normalRelativeAngleDegrees(absoluteBearing - getRadarHeading());
            setTurnRadarRight(radarTurn * 2);
        } else {
            setTurnRadarRight(360);
        }
    }

    private void choosePrimaryTarget() {
        if (detectedEnemies.isEmpty()) {
            primaryTarget = null;
            return;
        }
        enemigoObjetivo = enemigos.values().stream().min(Comparator.comparingDouble(EnemyInfo::getDistance)).orElse(null);
        tiempoUltimaVezVistoEnemigo = getTime();
    }

    private void broadcastPrimaryTarget() {
        if (primaryTarget != null) {
            try {
                broadcastMessage(new EnemyTarget(primaryTarget));
            } catch (IOException e) {
                logError("Failed to send EnemyTarget", e);
            }
        }
    }

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

    private double determineFirePower(double distance) {
        if (distance < 200) return MAX_FIRE_POWER;
        if (distance < 400) return 2.5;
        return MIN_FIRE_POWER;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(Math.min(value, max), min);
    }

    private void broadcastLocation() {
        try {
            broadcastMessage(new PositionUpdate(getName(), getX(), getY()));
        } catch (IOException e) {
            logError("Failed to send PositionUpdate", e);
        }
    }

    private void logError(String message, Exception e) {
        System.err.println(message);
        e.printStackTrace();
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent event) {
        if (isTeamMember(event.getName())) return;

        double absoluteBearing = Math.toRadians(getHeading() + event.getBearing());
        double enemyX = getX() + Math.sin(absoluteBearing) * event.getDistance();
        double enemyY = getY() + Math.cos(absoluteBearing) * event.getDistance();

        EnemyData enemyInfo = new EnemyData(
                event.getName(),
                event.getBearing(),
                event.getDistance(),
                event.getHeading(),
                event.getVelocity(),
                enemyX,
                enemyY,
                getTime(),
                event.getEnergy()
        );

        detectedEnemies.put(event.getName(), enemyInfo);

        try {
            sendMessage(currentCommander, enemyInfo);
        } catch (IOException e) {
            logError("Failed to send EnemyData to commander", e);
        }

        if (!isCommander && primaryTarget == null) return;

        if (primaryTarget != null && event.getName().equals(primaryTarget.getEnemyName())) {
            primaryTarget = enemyInfo;
            lastEnemySeenTime = getTime();
        }
    }

    private boolean isTeamMember(String robotName) {
        for (String member : teamMembers) {
            if (member.startsWith(robotName + "#")) return true;
        }
        return false;
    }

    @Override
    public void onHitRobot(HitRobotEvent event) {
        if (!isTeamMember(event.getName())) {
            setFire(2);
            back(RETRAER_DISTANCIA); // Corrected here
        } else {
            back(20);
        }
    }

    @Override
    public void onRobotDeath(RobotDeathEvent event) {
        String robotMuerto = event.getName();
        if (isTeammate(robotMuerto)) {
            miembrosVivos.remove(robotMuerto);
            posicionesRobots.remove(robotMuerto);

            if (robotMuerto.equals(nombreLider)) {
                if (miembrosVivos.contains(getName()) && jerarquia.get(getName()) == null) {
                    esLider = true;
                    nombreLider = getName();
                    jerarquia.remove(getName());

                    try {
                        broadcastMessage(new AnuncioLider(nombreLider, getX(), getY()));
                        broadcastMessage(new ActualizacionJerarquia(jerarquia));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    nombreLider = obtenerPredecesorVivo(robotMuerto);
                }
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

    @Override
    public void onPaint(Graphics2D g) {
        if (esLider) {
            g.setColor(java.awt.Color.YELLOW);
            int radio = 50;
            int diametro = radio * 2;
            int x = (int) (getX() - radio);
            int y = (int) (getY() - radio);
            g.drawOval(x, y, diametro, diametro);
        }
    }

    public void onMessageReceived(MessageEvent e) {
        Object mensaje = e.getMessage();
        if (mensaje instanceof PropuestaLider) {
            PropuestaLider propuesta = (PropuestaLider) mensaje;
            String nombreMiembro = propuesta.getRobotName() + "#" + propuesta.getRandomNumber();
            if (!miembrosEquipo.contains(nombreMiembro)) {
                miembrosEquipo.add(nombreMiembro);
                miembrosVivos.add(propuesta.getRobotName());
            }
        } else if (mensaje instanceof AnuncioLider) {
            AnuncioLider mensajeLider = (AnuncioLider) mensaje;
            nombreLider = mensajeLider.getRobotName();
            esLider = nombreLider.equals(getName());
            posicionesRobots.put(nombreLider, new Point2D.Double(mensajeLider.getX(), mensajeLider.getY()));
        } else if (mensaje instanceof ActualizacionPosicion) {
            ActualizacionPosicion actualizacionPosicion = (ActualizacionPosicion) mensaje;
            posicionesRobots.put(actualizacionPosicion.getRobotName(), new Point2D.Double(actualizacionPosicion.getX(), actualizacionPosicion.getY()));
        } else if (mensaje instanceof EnemyInfo) {
            EnemyInfo enemigoInfo = (EnemyInfo) mensaje;
            enemigos.put(enemigoInfo.getEnemyName(), enemigoInfo);
        } else if (mensaje instanceof MensajeEnemigoObjetivo) {
            MensajeEnemigoObjetivo tem = (MensajeEnemigoObjetivo) mensaje;
            enemigoObjetivo = tem.getEnemyInfo();
            tiempoUltimaVezVistoEnemigo = getTime();
        } else if (mensaje instanceof MensajeDistancia && esLider) {
            MensajeDistancia md = (MensajeDistancia) mensaje;
            distanciasDesdeRobots.put(md.getRobotName(), md.getDistance());
        } else if (mensaje instanceof ActualizacionJerarquia) {
            ActualizacionJerarquia aj = (ActualizacionJerarquia) mensaje;
            jerarquia = aj.getHierarchy();
        }
    }

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

    private void handleLeaderProposal(LeaderProposal proposal) {
        String name = proposal.getRobotName() + "#" + proposal.getRandomNumber();
        if (!teamMembers.contains(name)) {
            teamMembers.add(name);
            activeMembers.add(proposal.getRobotName());
        }
    }

    private void handleCommanderAnnouncement(CommanderAnnouncement announcement) {
        currentCommander = announcement.getCommanderName();
        isCommander = currentCommander.equals(getName());
        robotLocations.put(currentCommander, new Point2D.Double(announcement.getX(), announcement.getY()));
    }

    private void handlePositionUpdate(PositionUpdate update) {
        robotLocations.put(update.getRobotName(), new Point2D.Double(update.getX(), update.getY()));
    }

    private void handleEnemyData(EnemyData enemy) {
        detectedEnemies.put(enemy.getEnemyName(), enemy);
    }

    private void handleEnemyTarget(EnemyTarget targetMsg) {
        primaryTarget = targetMsg.getEnemyInfo();
        lastEnemySeenTime = getTime();
    }

    private void handleDistanceReport(DistanceReport report) {
        distancesFromCommander.put(report.getRobotName(), report.getDistance());
    }

    private void handleHierarchyUpdate(HierarchyUpdate update) {
        teamHierarchy.clear();
        teamHierarchy.putAll(update.getHierarchy());
    }

    // Classes Internes per Missatges
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

    static class EnemyTarget implements java.io.Serializable {
        private final EnemyData enemyInfo;

        public EnemyTarget(EnemyData enemyInfo) {
            this.enemyInfo = enemyInfo;
        }

        public EnemyData getEnemyInfo() {
            return enemyInfo;
        }
    }

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
