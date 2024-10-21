package pkg1;

import robocode.*;
import java.awt.geom.Point2D;
import robocode.util.Utils;

/**
 * Interfaz que define el estado del robot en Robocode.
 */
interface State {
    /**
     * Método que ejecuta las acciones correspondientes al estado actual.
     */
    void execute();
}

/**
 * Estado de la Fase 0: búsqueda de enemigo y cálculo de la esquina más lejana.
 */
class Phase0State implements State {
    private final TimidinRobot robot;

    /**
     * Constructor de la clase Phase0State.
     * 
     * @param robot Instancia del robot.
     */
    public Phase0State(TimidinRobot robot) {
        this.robot = robot;
    }

    @Override
    public void execute() {
        robot.setTurnRadarRight(Double.POSITIVE_INFINITY);  // Girar radar infinitamente
    }

    /**
     * Método llamado cuando se detecta un enemigo.
     * 
     * @param e Evento de detección de un robot enemigo.
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        double enemyX = robot.getX() + Math.sin(Math.toRadians(e.getBearing() + robot.getHeading())) * e.getDistance();
        double enemyY = robot.getY() + Math.cos(Math.toRadians(e.getBearing() + robot.getHeading())) * e.getDistance();
        Point2D.Double enemyPosition = new Point2D.Double(enemyX, enemyY);

        Point2D.Double farthestCorner = calculateFarthestCorner(enemyPosition);
        robot.setTargetCorner(farthestCorner);
        robot.changeState(robot.getPhase1State());  // Cambia a la Fase 1
    }

    /**
     * Calcula la esquina más lejana con respecto a la posición del enemigo.
     * 
     * @param enemyPosition Posición del enemigo.
     * @return La esquina más lejana del campo de batalla.
     */
    private Point2D.Double calculateFarthestCorner(Point2D.Double enemyPosition) {
        double battlefieldWidth = robot.getBattleFieldWidth();
        double battlefieldHeight = robot.getBattleFieldHeight();

        Point2D.Double[] corners = {
            new Point2D.Double(0, 0),
            new Point2D.Double(0, battlefieldHeight),
            new Point2D.Double(battlefieldWidth, 0),
            new Point2D.Double(battlefieldWidth, battlefieldHeight)
        };

        Point2D.Double farthestCorner = corners[0];
        double maxDistance = enemyPosition.distance(farthestCorner);

        for (Point2D.Double corner : corners) {
            double distance = enemyPosition.distance(corner);
            if (distance > maxDistance) {
                maxDistance = distance;
                farthestCorner = corner;
            }
        }
        return farthestCorner;
    }
}

/**
 * Estado de la Fase 1: movimiento hacia la esquina calculada, esquivando obstáculos y disparando si es necesario.
 */
class Phase1State implements State {
    private final TimidinRobot robot;
    private boolean obstaculo = false;
    private int stopCounter = 0;

    /**
     * Constructor de la clase Phase1State.
     * 
     * @param robot Instancia del robot.
     */
    public Phase1State(TimidinRobot robot) {
        this.robot = robot;
    }

    @Override
    public void execute() {
        Point2D.Double targetCorner = robot.getTargetCorner();
        if (targetCorner != null && !obstaculo) {
            double targetX = targetCorner.getX();
            double targetY = targetCorner.getY();

            // Calcular el ángulo hacia la esquina
            double angleToTarget = Math.toDegrees(Math.atan2(targetX - robot.getX(), targetY - robot.getY()));
            double angleTurn = Utils.normalRelativeAngleDegrees(angleToTarget - robot.getHeading());

            // Girar hacia la esquina
            robot.setTurnRight(angleTurn);

            // Avanzar si estamos alineados
            if (Math.abs(angleTurn) < 5) {
                double distanceToTarget = Point2D.distance(robot.getX(), robot.getY(), targetX, targetY);
                robot.setAhead(distanceToTarget);
            }

            // Alinear el radar en la misma dirección que el robot
            double radarTurn = Utils.normalRelativeAngleDegrees(angleToTarget - robot.getRadarHeading());
            robot.setTurnRadarRight(radarTurn);

            // Verificar si el robot está parado
            if (robot.getVelocity() == 0) {
                stopCounter++;
            } else {
                stopCounter = 0;
            }

            // Cambiar a la Fase 2 si está detenido durante 30 ticks
            if (stopCounter >= 30) {
                robot.changeState(robot.getPhase2State());
            }
        }

        if (obstaculo) {
            esquivarObs();
        }
    }

    /**
     * Método que ejecuta la acción de esquivar obstáculos.
     */
    public void esquivarObs() {
        double x = robot.getX();
        double y = robot.getY();
        double battlefieldWidth = robot.getBattleFieldWidth();
        double battlefieldHeight = robot.getBattleFieldHeight();

        double distanciaNorte = battlefieldHeight - y;
        double distanciaSur = y;
        double distanciaEste = battlefieldWidth - x;
        double distanciaOeste = x;

        // Determinar hacia qué lado girar
        if (distanciaNorte > distanciaSur && distanciaEste > distanciaOeste) {
            robot.turnLeft(45);
        } else {
            robot.turnRight(45);
        }

        robot.ahead(75);
        robot.execute();
        obstaculo = false;
    }

    /**
     * Método llamado cuando se detecta un robot enemigo cercano.
     * 
     * @param e Evento de detección de un robot enemigo.
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        if (e.getDistance() <= 200) {
            robot.setFire(2);
            obstaculo = true;
        }
    }

    /**
     * Método llamado cuando el robot choca con otro robot.
     * 
     * @param e Evento de colisión con un robot enemigo.
     */
    public void onHitRobot(HitRobotEvent e) {
        obstaculo = true;
    }
}

/**
 * Estado de la Fase 2: detección de enemigos, disparo y reinicio de búsqueda.
 */
class Phase2State implements State {
    private final TimidinRobot robot;

    /**
     * Constructor de la clase Phase2State.
     * 
     * @param robot Instancia del robot.
     */
    public Phase2State(TimidinRobot robot) {
        this.robot = robot;
    }

    @Override
    public void execute() {
        robot.setTurnRadarRight(Double.POSITIVE_INFINITY);  // Girar el radar buscando enemigos
    }

    /**
     * Método llamado cuando se detecta un enemigo.
     * 
     * @param e Evento de detección de un robot enemigo.
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        double radarTurn = robot.getHeading() - robot.getRadarHeading() + e.getBearing();
        robot.setTurnRadarRight(Utils.normalRelativeAngleDegrees(radarTurn));

        double gunTurn = robot.getHeading() - robot.getGunHeading() + e.getBearing();
        robot.setTurnGunRight(Utils.normalRelativeAngleDegrees(gunTurn));

        robot.smartFire(e.getDistance());
        robot.scan();
    }
}

/**
 * Clase principal del robot TimidinRobot que implementa una máquina de estados con tres fases.
 */
public class TimidinRobot extends AdvancedRobot {
    private State currentState;
    private final State phase0State = new Phase0State(this);
    private final State phase1State = new Phase1State(this);
    private final State phase2State = new Phase2State(this);
    private Point2D.Double targetCorner;

    @Override
    public void run() {
        currentState = phase0State;
        while (true) {
            currentState.execute();
            execute();
        }
    }

    /**
     * Cambia el estado actual del robot.
     * 
     * @param newState Nuevo estado al que cambiará el robot.
     */
    public void changeState(State newState) {
        currentState = newState;
    }

    public State getPhase1State() {
        return phase1State;
    }

    public State getPhase2State() {
        return phase2State;
    }

    /**
     * Método llamado cuando se detecta un robot enemigo.
     * 
     * @param e Evento de detección de un robot enemigo.
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        if (currentState instanceof Phase0State) {
            ((Phase0State) currentState).onScannedRobot(e);
        } else if (currentState instanceof Phase1State) {
            ((Phase1State) currentState).onScannedRobot(e);
        } else if (currentState instanceof Phase2State) {
            ((Phase2State) currentState).onScannedRobot(e);
        }
    }

    /**
     * Método llamado cuando el robot choca con otro robot.
     * 
     * @param e Evento de colisión con un robot enemigo.
     */
    public void onHitRobot(HitRobotEvent e) {
        if (currentState instanceof Phase1State) {
            ((Phase1State) currentState).onHitRobot(e);
        }
    }

    /**
     * Establece la esquina objetivo más lejana para que el robot se mueva hacia ella.
     * 
     * @param corner Esquina más lejana.
     */
    public void setTargetCorner(Point2D.Double corner) {
        this.targetCorner = corner;
    }

    /**
     * Devuelve la esquina objetivo más lejana.
     * 
     * @return Esquina más lejana.
     */
    public Point2D.Double getTargetCorner() {
        return targetCorner;
    }

    /**
     * Método que ajusta la potencia de disparo en función de la distancia al enemigo.
     * 
     * @param distance Distancia al robot enemigo.
     */
    public void smartFire(double distance) {
        if (distance > 400 || getEnergy() < 15) {
            setFire(1);
        } else if (distance > 200) {
            setFire(2);
        } else {
            setFire(3);
        }
    }
}
