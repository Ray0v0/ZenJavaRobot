package onebot.model;

/**
 * The sender of a message event. Maps OneBot v11 sender JSON object.
 */
public class Sender {

    private final long userId;
    private final String nickname;
    private final String card;
    private final String role;
    private final String sex;
    private final int age;
    private final int level;
    private final String title;

    public Sender(long userId, String nickname, String card, String role,
                  String sex, int age, int level, String title) {
        this.userId = userId;
        this.nickname = nickname != null ? nickname : "";
        this.card = card != null ? card : "";
        this.role = role != null ? role : "member";
        this.sex = sex != null ? sex : "unknown";
        this.age = age;
        this.level = level;
        this.title = title != null ? title : "";
    }

    // --- Getters ---

    public long getUserId() { return userId; }
    public String getNickname() { return nickname; }
    public String getCard() { return card; }
    public String getRole() { return role; }
    public String getSex() { return sex; }
    public int getAge() { return age; }
    public int getLevel() { return level; }
    public String getTitle() { return title; }

    /**
     * Best-effort display name: group card > nickname > user_id.
     */
    public String getDisplayName() {
        if (card != null && !card.isEmpty()) return card;
        if (nickname != null && !nickname.isEmpty()) return nickname;
        return String.valueOf(userId);
    }

    @Override
    public String toString() {
        return "Sender{userId=" + userId + ", displayName='" + getDisplayName() + "'}";
    }
}
