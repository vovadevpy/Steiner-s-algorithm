package org.example;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;


//дейкстра + штейнер
public class Djakstra extends JFrame {

    //настройки
    private static final int DIAMOND_MAX_DISTANCE = 1000;
    private static final int BALL_MAX_DISTANCE = 1000;
    private static final int SQUARE_MAX_DISTANCE = 1000;
    private static final int SQUARE_SIZE = 20;
    private static final int CYAN_DIAMOND_RADIUS = 12;
    private static final long DISCHARGE_DURATION = 1500;
    private static final int BASE_DAMAGE = 900;

    // максимум ячеек для точного поиска
    private static final long MAX_DP_CELLS = 2_000_000L;
    private static final long MAX_DP_OPERATIONS = 60_000_000L;

    // файл для записи
    private static final String LOG_FILE_PATH =
            "C:\\Users\\user\\Documents\\JavaProjects\\untitled6\\src\\main\\resources\\log.txt";
    private static PrintWriter logWriter = null;

    //основные данные
    private final List<Ball> balls = new ArrayList<>();
    private final Set<DischargePath> activeDischarges = new HashSet<>();
    private final List<Barrier> barriers = new ArrayList<>();
    private final List<Square> squares = new ArrayList<>();

    private final Timer gameTimer;
    private Square draggedSquare;
    private Point dragOffset;
    private boolean searchRunning;

    public Djakstra() {
        setTitle("Оптимизация сети разряда");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initLogger();

        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                setBackground(Color.WHITE);

                // рисуем квадраты и ромбы
                for (Square square : squares) {
                    g.setColor(square.color);
                    if (square.isDiamond) {
                        if (Color.CYAN.equals(square.color)) {
                            g.fillOval(square.x - CYAN_DIAMOND_RADIUS, square.y - CYAN_DIAMOND_RADIUS,
                                    CYAN_DIAMOND_RADIUS * 2, CYAN_DIAMOND_RADIUS * 2);
                        } else {
                            int half = SQUARE_SIZE / 2;
                            int[] xPoints = {square.x, square.x + half, square.x, square.x - half};
                            int[] yPoints = {square.y - half, square.y, square.y + half, square.y};
                            g.fillPolygon(xPoints, yPoints, 4);
                        }
                    } else {
                        int half = SQUARE_SIZE / 2;
                        g.fillRect(square.x - half, square.y - half, SQUARE_SIZE, SQUARE_SIZE);
                    }
                }

                //препятствия
                g.setColor(Color.BLACK);
                for (Barrier barrier : barriers) {
                    g.fillRect(barrier.x, barrier.y, barrier.width, barrier.height);
                }

                //линии разряда
                drawDischargePaths(g);

                //арики
                for (Ball ball : balls) {
                    g.setColor(ball.color);
                    g.fillOval(ball.x - ball.radius, ball.y - ball.radius, ball.radius * 2, ball.radius * 2);
                }
            }

            private void drawDischargePaths(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setStroke(new BasicStroke(3));
                g2d.setColor(new Color(0, 0, 255, 200));

                for (DischargePath discharge : activeDischarges) {
                    if (!discharge.pathExists) continue;
                    Point prev = getPoint(discharge.startInSegment);
                    if (discharge.path != null) {
                        for (Object obj : discharge.path) {
                            Point next = getPoint(obj);
                            g2d.drawLine(prev.x, prev.y, next.x, next.y);
                            prev = next;
                        }
                    }
                    g2d.drawLine(prev.x, prev.y, discharge.targetSquare.x, discharge.targetSquare.y);
                }
            }
        };

        gameTimer = new Timer(16, e -> {
            long now = System.currentTimeMillis();
            activeDischarges.removeIf(d -> now - d.time > DISCHARGE_DURATION);
            panel.repaint();
        });
        gameTimer.start();

        //обработка мыши
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (searchRunning) return;

                if (SwingUtilities.isLeftMouseButton(e)) {
                    draggedSquare = findSquareAtPoint(e.getPoint());
                    if (draggedSquare != null) {
                        dragOffset = new Point(e.getX() - draggedSquare.x, e.getY() - draggedSquare.y);
                    }
                }
                if (SwingUtilities.isRightMouseButton(e)) {
                    Object nearest = findNearestObject(e.getPoint());
                    if (nearest != null) {
                        activeDischarges.clear();
                        searchRunning = true;
                        logToFile("=== НАЧАЛО ТОЧНОГО ПОИСКА СЕТИ ===");
                        logToFile("Время: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
                        logToFile("Координаты клика: (" + e.getX() + ", " + e.getY() + ")");
                        logToFile("Стартовый объект: " + objectToString(nearest));

                        new SwingWorker<List<DischargePath>, Void>() {
                            @Override
                            protected List<DischargePath> doInBackground() {
                                return findOptimalNetwork(nearest);
                            }

                            @Override
                            protected void done() {
                                try {
                                    List<DischargePath> paths = get();
                                    long now = System.currentTimeMillis();
                                    for (DischargePath path : paths) path.time = now;
                                    activeDischarges.addAll(paths);
                                    calculateAndPrintTotalDamage();
                                } catch (Exception ex) {
                                    logToFile("Ошибка поиска сети: " + ex.getMessage());
                                    ex.printStackTrace();
                                } finally {
                                    searchRunning = false;
                                    logToFile("=== КОНЕЦ ПОИСКА СЕТИ ===\n");
                                    panel.repaint();
                                }
                            }
                        }.execute();
                    }
                }
                panel.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                draggedSquare = null;
            }
        });

        panel.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (draggedSquare != null) {
                    draggedSquare.x = e.getX() - dragOffset.x;
                    draggedSquare.y = e.getY() - dragOffset.y;
                    panel.repaint();
                }
            }
        });

        panel.setFocusable(true);
        panel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (searchRunning) return;
                Point m = panel.getMousePosition();
                if (m == null) return;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_K -> {
                        barriers.add(new Barrier(m.x - 25, m.y - 5, 50, 10));
                        logToFile("Добавлен барьер в (" + m.x + ", " + m.y + ")");
                    }
                    case KeyEvent.VK_1 -> {
                        squares.add(new Square(m.x, m.y, Color.RED, false));
                        logToFile("Добавлен красный квадрат в (" + m.x + ", " + m.y + ")");
                    }
                    case KeyEvent.VK_2 -> {
                        squares.add(new Square(m.x, m.y, Color.GREEN, true));
                        logToFile("Добавлен зеленый ромб в (" + m.x + ", " + m.y + ")");
                    }
                    case KeyEvent.VK_3 -> {
                        squares.add(new Square(m.x, m.y, Color.CYAN, true));
                        logToFile("Добавлен голубой ромб в (" + m.x + ", " + m.y + ")");
                    }
                    case KeyEvent.VK_B -> {
                        balls.add(new Ball(m.x, m.y, 10, Color.ORANGE));
                        logToFile("Добавлен шарик в (" + m.x + ", " + m.y + ")");
                    }
                    case KeyEvent.VK_C -> {
                        activeDischarges.clear();
                        squares.clear();
                        barriers.clear();
                        balls.clear();
                        logToFile("Очищены все объекты");
                    }
                }
                panel.repaint();
            }
        });

        add(panel);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeLogger();
                super.windowClosing(e);
            }
        });
    }

    private void initLogger() {
        try {
            File logFile = new File(LOG_FILE_PATH);
            if (logFile.getParentFile() != null) logFile.getParentFile().mkdirs();
            logWriter = new PrintWriter(new FileWriter(logFile, true));
            logToFile("=== ПРОГРАММА ЗАПУЩЕНА ===");
            logToFile("Время запуска: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            logToFile("Алгоритм: точная оптимизация общей сети");
            logToFile("=================================\n");
        } catch (IOException e) {
            System.err.println("Ошибка инициализации логгера: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private synchronized void logToFile(String message) {
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
        System.out.println("LOG: " + message);
    }

    private void closeLogger() {
        if (logWriter != null) {
            logToFile("\n=== ПРОГРАММА ЗАВЕРШЕНА ===");
            logToFile("Время завершения: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            logWriter.close();
        }
    }

    private String objectToString(Object obj) {
        if (obj instanceof Square s) {
            String type = s.isDiamond
                    ? (Color.CYAN.equals(s.color) ? "голубой_ромб" : "зеленый_ромб")
                    : (Color.RED.equals(s.color) ? "красный_квадрат" : "обычный_квадрат");
            return type + "(" + s.x + "," + s.y + ")";
        } else if (obj instanceof Ball b) {
            return "шарик(" + b.x + "," + b.y + ")";
        }
        return "неизвестный_объект";
    }

    //поиск общей сети
    private List<DischargePath> findOptimalNetwork(Object startSource) {
        List<Object> vertices = new ArrayList<>();
        vertices.add(startSource);

        for (Square square : squares) {
            if (square != startSource) vertices.add(square);
        }
        for (Ball ball : balls) {
            if (ball != startSource) vertices.add(ball);
        }

        Map<Object, Integer> indexByObject = new HashMap<>();
        for (int i = 0; i < vertices.size(); i++) {
            indexByObject.put(vertices.get(i), i);
        }

        int root = indexByObject.get(startSource);
        int vertexCount = vertices.size();
        List<List<NetworkEdge>> outgoing = new ArrayList<>();
        List<List<NetworkEdge>> incoming = new ArrayList<>();
        for (int i = 0; i < vertexCount; i++) {
            outgoing.add(new ArrayList<>());
            incoming.add(new ArrayList<>());
        }

        // постройка всех допустымых обьектов
        for (int from = 0; from < vertexCount; from++) {
            Object source = vertices.get(from);

            Point p1 = getPoint(source);
            int maxDistance = getMaxConnectionDistance(source);

            for (int to = 0; to < vertexCount; to++) {
                if (from == to) continue;

                Object target = vertices.get(to);
                Point p2 = getPoint(target);
                double distance = p1.distance(p2);

                if (distance > maxDistance) continue;
                if (lineIntersectsBarrier(p1, p2)) continue;

                NetworkCost edgeCost = new NetworkCost(
                        1,
                        distance,
                        isSpecial(target) ? 1 : 0,
                        isNormalSquare(target) ? 1 : 0
                );
                NetworkEdge edge = new NetworkEdge(from, to, edgeCost);
                outgoing.get(from).add(edge);
                incoming.get(to).add(edge);
            }
        }

        //только красные квадраты
        List<Integer> terminals = findReachableRedSquares(root, vertices, outgoing);
        logToFile("Всего красных квадратов: " + squares.stream().filter(this::isRedSquare).count());
        logToFile("Достижимых красных квадратов: " + terminals.size());

        if (terminals.isEmpty()) {
            logToFile("Нет доступных красных квадратов");
            return new ArrayList<>();
        }

        int terminalCount = terminals.size();
        if (terminalCount >= 31) {
            logToFile("Слишком много целей для точного поиска с битовой маской: " + terminalCount);
            logToFile("Приближенный результат не подставляеся");
            return new ArrayList<>();
        }

        int maskCount = 1 << terminalCount;
        long cellCount = (long) vertexCount * maskCount;
        if (cellCount > MAX_DP_CELLS) {
            logToFile("Точный поиск остановлен: требуется " + cellCount +
                    " состояний, лимит памяти — " + MAX_DP_CELLS);
            logToFile("Уменьши число объектов или красных целей и повтори поиск");
            return new ArrayList<>();
        }

        long partitionCount = 1;
        for (int i = 0; i < terminalCount; i++) {
            if (partitionCount > MAX_DP_OPERATIONS / 3) {
                partitionCount = MAX_DP_OPERATIONS + 1;
                break;
            }
            partitionCount *= 3;
        }
        long estimatedOperations = partitionCount > MAX_DP_OPERATIONS
                ? partitionCount : partitionCount * vertexCount;
        if (estimatedOperations > MAX_DP_OPERATIONS) {
            logToFile("Точный поиск остановлен: слишком много комбинаций для текущего числа целей");
            logToFile("Оценка операций: " + estimatedOperations + ", лимит: " + MAX_DP_OPERATIONS);
            logToFile("Приближенный результат не подставляется");
            return new ArrayList<>();
        }

        logToFile("Запуск точной оптимизации сети");
        logToFile("Число вершин: " + vertexCount + ", число целей: " + terminalCount);

        DpState[][] dp = new DpState[vertexCount][maskCount];

        //каждая красная цель начинает свой набор
        for (int i = 0; i < terminalCount; i++) {
            int mask = 1 << i;
            dp[terminals.get(i)][mask] = new DpState(NetworkCost.ZERO, DpState.BASE, -1);
        }

        //перебираем наборы целей от меньших к большим
        for (int mask = 1; mask < maskCount; mask++) {
            //пробуем разделить цели на две ветви из одной вершины (вообще не обязательноено, но для точности необходимо)
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                for (int part = (mask - 1) & mask; part > 0; part = (part - 1) & mask) {
                    int otherPart = mask ^ part;
                    if (otherPart == 0 || part > otherPart) continue;

                    DpState left = dp[vertex][part];
                    DpState right = dp[vertex][otherPart];
                    if (left == null || right == null) continue;

                    NetworkCost combined = left.cost.plus(right.cost);
                    DpState old = dp[vertex][mask];
                    if (old == null || combined.compareTo(old.cost) < 0) {
                        dp[vertex][mask] = new DpState(combined, DpState.SPLIT, part);
                    }
                }
            }

            //распространение лучшие варианты назад по ребрам графа
            PriorityQueue<QueueEntry> queue = new PriorityQueue<>(
                    (a, b) -> a.state.cost.compareTo(b.state.cost)
            );

            for (int vertex = 0; vertex < vertexCount; vertex++) {
                if (dp[vertex][mask] != null) {
                    queue.add(new QueueEntry(vertex, dp[vertex][mask]));
                }
            }

            while (!queue.isEmpty()) {
                QueueEntry current = queue.poll();
                if (dp[current.vertex][mask] != current.state) continue;

                for (NetworkEdge edge : incoming.get(current.vertex)) {
                    int previous = edge.from;
                    NetworkCost candidate = current.state.cost.plus(edge.cost);
                    DpState old = dp[previous][mask];

                    if (old == null || candidate.compareTo(old.cost) < 0) {
                        DpState nextState = new DpState(candidate, DpState.NEXT, current.vertex);
                        dp[previous][mask] = nextState;
                        queue.add(new QueueEntry(previous, nextState));
                    }
                }
            }
        }

        int allTargetsMask = maskCount - 1;
        DpState best = dp[root][allTargetsMask];
        if (best == null) {
            logToFile("Не удалось построить общую сеть для всех достижимых целей");
            return new ArrayList<>();
        }

        logToFile("Оптимальная сеть найдена");
        logToFile("Подключено красных квадратов: " + terminalCount);
        logToFile("Уникальных переходов: " + best.cost.jumps);
        logToFile("Общая длина сети: " + String.format(Locale.US, "%.2f", best.cost.distance));
        logToFile("Специальных объектов: " + best.cost.specialCount);
        logToFile("Обычных промежуточных квадратов: " + best.cost.bridgeCount);

        // восстанавливае общей сети
        Map<Integer, Integer> parent = new HashMap<>();
        Set<Long> visitedStates = new HashSet<>();
        collectNetwork(root, allTargetsMask, dp, parent, visitedStates);

        // для отображения  постройка линии от старта
        List<DischargePath> result = new ArrayList<>();
        for (int terminal : terminals) {
            List<Object> reversedPath = new ArrayList<>();
            int current = terminal;
            boolean valid = true;
            Set<Integer> seen = new HashSet<>();

            while (current != root) {
                if (!seen.add(current)) {
                    valid = false;
                    break;
                }

                Integer previous = parent.get(current);
                if (previous == null) {
                    valid = false;
                    break;
                }

                current = previous;
                if (current != root) reversedPath.add(vertices.get(current));
            }

            if (!valid) {
                logToFile("не удалось восстановить путь к одной из целей");
                continue;
            }

            Collections.reverse(reversedPath);
            Square target = (Square) vertices.get(terminal);
            DischargePath discharge = new DischargePath(startSource, reversedPath, target);
            discharge.pathExists = true;
            result.add(discharge);
            logToFile("Путь к " + objectToString(target) + ": " + (reversedPath.size() + 1) + " переходов");
        }

        return result;
    }

    private List<Integer> findReachableRedSquares(int root,
                                                  List<Object> vertices,
                                                  List<List<NetworkEdge>> outgoing) {
        boolean[] visited = new boolean[vertices.size()];
        Queue<Integer> queue = new ArrayDeque<>();
        List<Integer> result = new ArrayList<>();

        visited[root] = true;
        queue.add(root);

        while (!queue.isEmpty()) {
            int current = queue.poll();

            for (NetworkEdge edge : outgoing.get(current)) {
                int next = edge.to;
                Object object = vertices.get(next);

                if (isRedSquare(object) && next != root && !result.contains(next)) {
                    result.add(next);
                }
                if (!visited[next]) {
                    visited[next] = true;
                    queue.add(next);
                }
            }
        }

        return result;
    }

    private void collectNetwork(int vertex, int mask, DpState[][] dp,
                                Map<Integer, Integer> parent, Set<Long> visitedStates) {
        long stateKey = (((long) mask) << 32) | (vertex & 0xffffffffL);
        if (!visitedStates.add(stateKey)) return;

        DpState state = dp[vertex][mask];
        if (state == null) return;

        if (state.type == DpState.SPLIT) {
            int firstPart = state.value;
            collectNetwork(vertex, firstPart, dp, parent, visitedStates);
            collectNetwork(vertex, mask ^ firstPart, dp, parent, visitedStates);
        } else if (state.type == DpState.NEXT) {
            int next = state.value;
            Integer oldParent = parent.putIfAbsent(next, vertex);
            if (oldParent != null && oldParent != vertex) {
                logToFile("Предупреждение: найдено несколько родителей у одной вершины");
            }
            collectNetwork(next, mask, dp, parent, visitedStates);
        }
    }

    //доп проверки
    private boolean isNormalSquare(Object obj) {
        return (obj instanceof Square s) && !s.isDiamond && !Color.RED.equals(s.color);
    }

    private boolean isRedSquare(Object obj) {
        return (obj instanceof Square s) && !s.isDiamond && Color.RED.equals(s.color);
    }

    private boolean isDiamond(Object obj) {
        return (obj instanceof Square s) && s.isDiamond;
    }

    private boolean isSpecial(Object obj) {
        return (obj instanceof Ball) || isDiamond(obj);
    }

    private int getMaxConnectionDistance(Object obj) {
        if (obj instanceof Square s) {
            if (s.isDiamond) {
                return Color.CYAN.equals(s.color) ? BALL_MAX_DISTANCE : DIAMOND_MAX_DISTANCE;
            }
            return SQUARE_MAX_DISTANCE;
        }
        return BALL_MAX_DISTANCE;
    }

    private Point getPoint(Object obj) {
        if (obj instanceof Square s) return new Point(s.x, s.y);
        if (obj instanceof Ball b) return new Point(b.x, b.y);
        return new Point(0, 0);
    }

    private Square findSquareAtPoint(Point p) {
        return squares.stream().filter(s -> s.distance(p) < SQUARE_SIZE).findFirst().orElse(null);
    }

    private Object findNearestObject(Point p) {
        Square s = squares.stream().min(Comparator.comparingDouble(sq -> sq.distance(p))).orElse(null);
        Ball b = balls.stream().min(Comparator.comparingDouble(ba -> ba.distance(p))).orElse(null);
        if (s == null) return b;
        if (b == null) return s;
        return s.distance(p) < b.distance(p) ? s : b;
    }

    private void calculateAndPrintTotalDamage() {
        Set<Object> unique = new HashSet<>();
        for (DischargePath dp : activeDischarges) {
            if (!isDiamond(dp.startInSegment)) unique.add(dp.startInSegment);
            for (Object o : dp.path) if (!isDiamond(o)) unique.add(o);
            if (!isDiamond(dp.targetSquare)) unique.add(dp.targetSquare);
        }
        int count = Math.max(1, unique.size());
        int damage = BASE_DAMAGE / count;

        logToFile("расчёт урона (деление)");
        logToFile("Базовый урон: " + BASE_DAMAGE);
        logToFile("Уникальных объектов в сети: " + count);
        logToFile("Итоговый урон: " + damage);

        System.out.println("Урон: " + damage + " | Объектов: " + count);
    }

    private boolean lineIntersectsBarrier(Point p1, Point p2) {
        for (Barrier b : barriers) {
            if (new Rectangle(b.x, b.y, b.width, b.height).intersectsLine(p1.x, p1.y, p2.x, p2.y)) {
                return true;
            }
        }
        return false;
    }

    // классы поиска сетей
    private static class NetworkEdge {
        int from;
        int to;
        NetworkCost cost;

        NetworkEdge(int from, int to, NetworkCost cost) {
            this.from = from;
            this.to = to;
            this.cost = cost;
        }
    }

    private static class NetworkCost implements Comparable<NetworkCost> {
        static final NetworkCost ZERO = new NetworkCost(0, 0, 0, 0);

        int jumps;
        double distance;
        int specialCount;
        int bridgeCount;

        NetworkCost(int jumps, double distance, int specialCount, int bridgeCount) {
            this.jumps = jumps;
            this.distance = distance;
            this.specialCount = specialCount;
            this.bridgeCount = bridgeCount;
        }

        NetworkCost plus(NetworkCost other) {
            return new NetworkCost(
                    jumps + other.jumps,
                    distance + other.distance,
                    specialCount + other.specialCount,
                    bridgeCount + other.bridgeCount
            );
        }

        @Override
        public int compareTo(NetworkCost other) {
            int result = Integer.compare(jumps, other.jumps);
            if (result != 0) return result;

            result = Double.compare(distance, other.distance);
            if (result != 0) return result;

            result = Integer.compare(specialCount, other.specialCount);
            if (result != 0) return result;

            return Integer.compare(bridgeCount, other.bridgeCount);
        }
    }

    private static class DpState {
        static final int BASE = 0;
        static final int SPLIT = 1;
        static final int NEXT = 2;

        NetworkCost cost;
        int type;
        int value;

        DpState(NetworkCost cost, int type, int value) {
            this.cost = cost;
            this.type = type;
            this.value = value;
        }
    }

    private static class QueueEntry {
        int vertex;
        DpState state;

        QueueEntry(int vertex, DpState state) {
            this.vertex = vertex;
            this.state = state;
        }
    }

    //классы обьектов и путией
    private static class Square {
        int x, y;
        Color color;
        boolean isDiamond;

        Square(int x, int y, Color c, boolean d) {
            this.x = x;
            this.y = y;
            this.color = c;
            this.isDiamond = d;
        }

        double distance(Point p) {
            return p.distance(x, y);
        }
    }

    private static class Ball {
        int x, y, radius;
        Color color;

        Ball(int x, int y, int r, Color c) {
            this.x = x;
            this.y = y;
            this.radius = r;
            this.color = c;
        }

        double distance(Point p) {
            return p.distance(x, y);
        }
    }

    private static class Barrier {
        int x, y, width, height;

        Barrier(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.width = w;
            this.height = h;
        }
    }

    private static class DischargePath {
        Object startInSegment;
        List<Object> path;
        Square targetSquare;
        long time = System.currentTimeMillis();
        boolean pathExists;

        DischargePath(Object s, List<Object> p, Square t) {
            this.startInSegment = s;
            this.path = p;
            this.targetSquare = t;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Djakstra().setVisible(true));
    }
}