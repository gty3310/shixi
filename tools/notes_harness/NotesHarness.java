import com.ti.songdu.NotesDigest;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.23.0 笔记聚合桌面 harness：直驱 NotesDigest 本体。
 * 运行：javac -d <out> -sourcepath app/src/main/java tools/notes_harness/NotesHarness.java
 */
public class NotesHarness {
    static int passed = 0, failed = 0;
    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }
    static NotesDigest.Entry e(String book, int flat, int mark, String note) {
        NotesDigest.Entry en = new NotesDigest.Entry();
        en.bookId = book; en.bookTitle = book; en.flat = flat; en.mark = mark; en.note = note;
        en.sentId = book + "-" + flat;
        return en;
    }
    public static void main(String[] args) {
        List<NotesDigest.Entry> in = new ArrayList<>();
        in.add(e("lunyu", 30, 0, "论语笔记二"));
        in.add(e("daxue", 12, 1, "大学笔记二"));
        in.add(e("daxue", 3, 0, "大学笔记一"));
        in.add(e("daxue", 7, 2, ""));            // 空笔记 -> 排除
        in.add(e("daxue", 9, 3, "   "));          // 纯空白 -> 排除
        in.add(e("zhongyong", 5, 1, null));      // mark-only（note null）-> 排除
        in.add(e("lunyu", 4, 0, "论语笔记一"));
        in.add(e("mengzi", 1, 0, "孟子笔记"));
        List<NotesDigest.Group> gs = NotesDigest.group(in);
        check("groups: 3 books kept (zhongyong mark-only dropped)", gs.size() == 3);
        check("groups: canon order daxue < lunyu < mengzi",
                gs.get(0).bookId.equals("daxue") && gs.get(1).bookId.equals("lunyu")
                        && gs.get(2).bookId.equals("mengzi"));
        check("groups: daxue has 2 (empty/blank filtered)", gs.get(0).items.size() == 2);
        check("groups: within-book sorted by flat asc",
                gs.get(0).items.get(0).flat == 3 && gs.get(0).items.get(1).flat == 12
                        && gs.get(1).items.get(0).flat == 4 && gs.get(1).items.get(1).flat == 30);
        check("groups: empty input -> no groups", NotesDigest.group(new ArrayList<>()).isEmpty());
        check("groups: null input -> no groups", NotesDigest.group(null).isEmpty());
        System.out.println("NotesHarness: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
