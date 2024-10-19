package josepjiahla;

import java.awt.Color;
import robocode.*;
import robocode.util.Utils;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.*;

public class FollowTheLeaderRobot extends TeamRobot {
    private boolean esLider = false;
    private String nombreLider = null;
    private List<String> miembrosEquipo = new ArrayList<>();
    private TreeMap<String, Point2D.Double> posicionesRobots = new TreeMap<>(); // Cambiado a TreeMap
    private Map<String, String> jerarquia = new LinkedHashMap<>();
    private Set<String> miembrosVivos = new HashSet<>();
    private List<Point2D.Double> esquinas = new ArrayList<>();
    private int indiceEsquinaActual = -1;
    private boolean sentidoHorario = true;
    private long tiempoUltimoCambioRol = 0;
    private TreeMap<String, EnemyInfo> enemigos = new TreeMap<>(); // Cambiado a TreeMap
    private EnemyInfo enemigoObjetivo = null;
    private long tiempoUltimaVezVistoEnemigo = 0;
    private TreeMap<String, Double> distanciasDesdeRobots = new TreeMap<>(); // Cambiado a TreeMap
    private int mensajesEsperadosDistancia = 0;
    private static final double MAX_PODER_DISPARO = 3.0;
    private static final double MIN_PODER_DISPARO = 1.0;

    public void run() {
        
        setColors(Color.BLACK, Color.GREEN, Color.GREEN);
        iniciarYRealizarHandshake();
        tiempoUltimoCambioRol = getTime();

        while (!jerarquia.containsKey(getName()) && !esLider) {
            execute();
        }

        for (String miembro : miembrosEquipo) {
            miembrosVivos.add(miembro.split("#")[0]);
        }

        long tiempoUltimaActualizacionPosicion = 0;

        while (true) {
            if (getTime() - tiempoUltimoCambioRol >= 300) {
                rotarRoles();
                tiempoUltimoCambioRol = getTime();
            }

            if (esLider) {
                moverLider();
                seleccionarEnemigoObjetivo();
                transmitirEnemigoObjetivo();
            } else {
                seguirPredecesor();
            }

            controlarRadar();

            if (enemigoObjetivo != null && (getTime() - tiempoUltimaVezVistoEnemigo) < 8) {
                rastrearYDisparar();
            }

            if (getTime() - tiempoUltimaActualizacionPosicion >= 5) {
                try {
                    broadcastMessage(new ActualizacionPosicion(getName(), getX(), getY()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                tiempoUltimaActualizacionPosicion = getTime();
            }

            execute();
        }
    }

    // Orden reestructurado de las funciones privadas, pero sin cambiar su lógica.

    private void definirEsquinasCampoBatalla() {
        double margenX = getBattleFieldWidth() * 0.1;
        double margenY = getBattleFieldHeight() * 0.1;
        esquinas.add(new Point2D.Double(margenX, margenY));
        esquinas.add(new Point2D.Double(getBattleFieldWidth() - margenX, margenY));
        esquinas.add(new Point2D.Double(getBattleFieldWidth() - margenX, getBattleFieldHeight() - margenY));
        esquinas.add(new Point2D.Double(margenX, getBattleFieldHeight() - margenY));
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
        definirEsquinasCampoBatalla();
        realizarHandshake();
    }

    private void construirJerarquia() {
        List<Map.Entry<String, Double>> entradasOrdenadas = new ArrayList<>(distanciasDesdeRobots.entrySet());
        Collections.sort(entradasOrdenadas, Comparator.comparingDouble(Map.Entry::getValue));

        jerarquia = new LinkedHashMap<>();
        String robotPrevio = nombreLider;
        for (Map.Entry<String, Double> entry : entradasOrdenadas) {
            String nombreRobot = entry.getKey();
            jerarquia.put(nombreRobot, robotPrevio);
            robotPrevio = nombreRobot;
        }
    }

    private int obtenerNumeroAleatorioDeNombre(String nombre) {
        String[] partes = nombre.split("#");
        return partes.length > 1 ? Integer.parseInt(partes[1]) : 0;
    }

    private void establecerJerarquia() {
        if (esLider) {
            try {
                broadcastMessage(new AnuncioLider(getName(), getX(), getY()));
            } catch (IOException e) {
                e.printStackTrace();
            }

            distanciasDesdeRobots = new TreeMap<>();
            mensajesEsperadosDistancia = miembrosEquipo.size() - 1;

            while (distanciasDesdeRobots.size() < mensajesEsperadosDistancia) {
                execute();
            }

            construirJerarquia();

            try {
                broadcastMessage(new ActualizacionJerarquia(jerarquia));
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            while (!posicionesRobots.containsKey(nombreLider)) {
                execute();
            }

            double distanciaLider = Point2D.distance(getX(), getY(), posicionesRobots.get(nombreLider).getX(), posicionesRobots.get(nombreLider).getY());
            try {
                sendMessage(nombreLider, new MensajeDistancia(getName(), distanciaLider));
            } catch (IOException e) {
                e.printStackTrace();
            }

            while (!jerarquia.containsKey(getName())) {
                execute();
            }
        }
    }

    private void rotarRoles() {
        sentidoHorario = !sentidoHorario;
        List<String> miembros = new ArrayList<>(jerarquia.keySet());
        miembros.add(0, nombreLider);

        String nuevoLider = null;
        for (int i = miembros.size() - 1; i >= 0; i--) {
            String candidato = miembros.get(i);
            if (miembrosVivos.contains(candidato)) {
                nuevoLider = candidato;
                break;
            }
        }

        if (nuevoLider != null) {
            nombreLider = nuevoLider;
            esLider = nombreLider.equals(getName());

            jerarquia.clear();
            String robotPrevio = nombreLider;
            for (String miembro : miembros) {
                if (miembrosVivos.contains(miembro)) {
                    jerarquia.put(miembro, robotPrevio);
                    robotPrevio = miembro;
                }
            }

            try {
                broadcastMessage(new AnuncioLider(nombreLider, getX(), getY()));
                broadcastMessage(new ActualizacionJerarquia(jerarquia));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void moverLider() {
        if (indiceEsquinaActual == -1) {
            indiceEsquinaActual = obtenerIndiceEsquinaMasCercana();
        }

        Point2D.Double esquinaObjetivo = esquinas.get(indiceEsquinaActual);
        irA(esquinaObjetivo.getX(), esquinaObjetivo.getY());

        if (obtenerDistanciaA(esquinaObjetivo) < 20) {
            indiceEsquinaActual = sentidoHorario ? (indiceEsquinaActual - 1 + esquinas.size()) % esquinas.size() : (indiceEsquinaActual + 1) % esquinas.size();
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

    private int obtenerIndiceEsquinaMasCercana() {
        double distanciaMinima = Double.MAX_VALUE;
        int indice = 0;
        for (int i = 0; i < esquinas.size(); i++) {
            double distancia = obtenerDistanciaA(esquinas.get(i));
            if (distancia < distanciaMinima) {
                distanciaMinima = distancia;
                indice = i;
            }
        }
        return indice;
    }

    private double obtenerDistanciaA(Point2D.Double punto) {
        return Point2D.distance(getX(), getY(), punto.getX(), punto.getY());
    }

    private void irA(double x, double y) {
        double dx = x - getX();
        double dy = y - getY();
        double anguloObjetivo = Math.toDegrees(Math.atan2(dx, dy));
        double anguloParaGirar = Utils.normalRelativeAngleDegrees(anguloObjetivo - getHeading());
        setTurnRight(anguloParaGirar);
        setAhead(Math.hypot(dx, dy));
    }

    private String obtenerPredecesorVivo(String nombreRobot) {
        String predecesor = jerarquia.get(nombreRobot);
        while (predecesor != null && !miembrosVivos.contains(predecesor)) {
            predecesor = jerarquia.get(predecesor);
        }
        return predecesor == null && !nombreRobot.equals(nombreLider) ? nombreLider : predecesor;
    }

    private void controlarRadar() {
        if (enemigoObjetivo != null) {
            double anguloAbsoluto = Math.toDegrees(Math.atan2(enemigoObjetivo.getX() - getX(), enemigoObjetivo.getY() - getY()));
            double giroRadar = Utils.normalRelativeAngleDegrees(anguloAbsoluto - getRadarHeading());
            setTurnRadarRight(giroRadar * 2);
        } else {
            setTurnRadarRight(360);
        }
    }

    private void seleccionarEnemigoObjetivo() {
        if (enemigos.isEmpty()) {
            enemigoObjetivo = null;
            return;
        }

        enemigoObjetivo = enemigos.values().stream().min(Comparator.comparingDouble(EnemyInfo::getDistance)).orElse(null);
        tiempoUltimaVezVistoEnemigo = getTime();
    }

    private void transmitirEnemigoObjetivo() {
        if (enemigoObjetivo != null) {
            try {
                broadcastMessage(new MensajeEnemigoObjetivo(enemigoObjetivo));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void rastrearYDisparar() {
        double poderBala = obtenerPoderDisparoOptimo(enemigoObjetivo.getDistance());
        double velocidadBala = Rules.getBulletSpeed(poderBala);
        double tiempoImpacto = enemigoObjetivo.getDistance() / velocidadBala;

        double futuroX = enemigoObjetivo.getX() + Math.sin(Math.toRadians(enemigoObjetivo.getHeading())) * enemigoObjetivo.getVelocity() * tiempoImpacto;
        double futuroY = enemigoObjetivo.getY() + Math.cos(Math.toRadians(enemigoObjetivo.getHeading())) * enemigoObjetivo.getVelocity() * tiempoImpacto;

        futuroX = Math.max(Math.min(futuroX, getBattleFieldWidth() - 18), 18);
        futuroY = Math.max(Math.min(futuroY, getBattleFieldHeight() - 18), 18);

        double anguloEnemigo = Math.toDegrees(Math.atan2(futuroX - getX(), futuroY - getY()));
        double giroCañon = Utils.normalRelativeAngleDegrees(anguloEnemigo - getGunHeading());

        setTurnGunRight(giroCañon);

        if (Math.abs(giroCañon) < 10) {
            setFire(poderBala);
        }
    }

    private double obtenerPoderDisparoOptimo(double distancia) {
        return distancia < 200 ? MAX_PODER_DISPARO : (distancia < 400 ? 2.5 : MIN_PODER_DISPARO);
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        if (isTeammate(e.getName())) return;

        double anguloAbsoluto = Math.toRadians(getHeading() + e.getBearing());
        double enemigoX = getX() + Math.sin(anguloAbsoluto) * e.getDistance();
        double enemigoY = getY() + Math.cos(anguloAbsoluto) * e.getDistance();

        EnemyInfo enemigoInfo = new EnemyInfo(e.getName(), e.getBearing(), e.getDistance(), e.getHeading(), e.getVelocity(), enemigoX, enemigoY, getTime());

        enemigos.put(e.getName(), enemigoInfo);

        try {
            sendMessage(nombreLider, enemigoInfo);
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        if (!esLider && enemigoObjetivo == null) return;

        if (enemigoObjetivo != null && e.getName().equals(enemigoObjetivo.getEnemyName())) {
            enemigoObjetivo = enemigoInfo;
            tiempoUltimaVezVistoEnemigo = getTime();
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

    public void onRobotDeath(RobotDeathEvent event) {
        String robotMuerto = event.getName();
        if (isTeammate(robotMuerto)) {
            miembrosVivos.remove(robotMuerto);

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

            if (jerarquia.containsKey(robotMuerto)) {
                jerarquia.remove(robotMuerto);
            }

            for (Map.Entry<String, String> entry : jerarquia.entrySet()) {
                String nombreRobot = entry.getKey();
                String predecesor = entry.getValue();

                if (predecesor.equals(robotMuerto)) {
                    String nuevoPredecesor = obtenerPredecesorVivo(nombreRobot);
                    jerarquia.put(nombreRobot, nuevoPredecesor);
                }
            }
        } else {
            enemigos.remove(robotMuerto);
            if (enemigoObjetivo != null && enemigoObjetivo.getEnemyName().equals(robotMuerto)) {
                enemigoObjetivo = null;
            }
        }
    }

    public void onPaint(Graphics2D g) {
        if (esLider) {
            g.setColor(java.awt.Color.yellow);
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

    // Clases internas
    static class PropuestaLider implements java.io.Serializable {
        private String nombreRobot;
        private int numeroAleatorio;

        public PropuestaLider(String nombreRobot, int numeroAleatorio) {
            this.nombreRobot = nombreRobot;
            this.numeroAleatorio = numeroAleatorio;
        }

        public String getRobotName() {
            return nombreRobot;
        }

        public int getRandomNumber() {
            return numeroAleatorio;
        }
    }

    static class AnuncioLider implements java.io.Serializable {
        private String nombreRobot;
        private double x, y;

        public AnuncioLider(String nombreRobot, double x, double y) {
            this.nombreRobot = nombreRobot;
            this.x = x;
            this.y = y;
        }

        public String getRobotName() {
            return nombreRobot;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }
    }

    static class ActualizacionPosicion implements java.io.Serializable {
        private String nombreRobot;
        private double x, y;

        public ActualizacionPosicion(String nombreRobot, double x, double y) {
            this.nombreRobot = nombreRobot;
            this.x = x;
            this.y = y;
        }

        public String getRobotName() {
            return nombreRobot;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }
    }

    static class MensajeDistancia implements java.io.Serializable {
        private String nombreRobot;
        private double distancia;

        public MensajeDistancia(String nombreRobot, double distancia) {
            this.nombreRobot = nombreRobot;
            this.distancia = distancia;
        }

        public String getRobotName() {
            return nombreRobot;
        }

        public double getDistance() {
            return distancia;
        }
    }

    static class EnemyInfo implements java.io.Serializable {
        private String nombreEnemigo;
        private double bearing;
        private double distancia;
        private double heading;
        private double velocidad;
        private double x, y;
        private long tiempo;

        public EnemyInfo(String nombreEnemigo, double bearing, double distancia, double heading, double velocidad, double x, double y, long tiempo) {
            this.nombreEnemigo = nombreEnemigo;
            this.bearing = bearing;
            this.distancia = distancia;
            this.heading = heading;
            this.velocidad = velocidad;
            this.x = x;
            this.y = y;
            this.tiempo = tiempo;
        }

        public String getEnemyName() {
            return nombreEnemigo;
        }

        public double getBearing() {
            return bearing;
        }

        public double getDistance() {
            return distancia;
        }

        public double getHeading() {
            return heading;
        }

        public double getVelocity() {
            return velocidad;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public long getTime() {
            return tiempo;
        }
    }

    static class MensajeEnemigoObjetivo implements java.io.Serializable {
        private EnemyInfo enemigoInfo;

        public MensajeEnemigoObjetivo(EnemyInfo enemigoInfo) {
            this.enemigoInfo = enemigoInfo;
        }

        public EnemyInfo getEnemyInfo() {
            return enemigoInfo;
        }
    }

    static class ActualizacionJerarquia implements java.io.Serializable {
        private Map<String, String> jerarquia;

        public ActualizacionJerarquia(Map<String, String> jerarquia) {
            this.jerarquia = jerarquia;
        }

        public Map<String, String> getHierarchy() {
            return jerarquia;
        }
    }
}
