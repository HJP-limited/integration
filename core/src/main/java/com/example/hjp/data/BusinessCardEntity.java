package com.example.hjp.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "business_cards")
public class BusinessCardEntity {
    @PrimaryKey
    @NonNull
    public String id;
    public String name;
    public String nameEn;
    public String company;
    public String title;
    public String department;
    public String industry;
    public String location;
    public String phone;
    public String email;
    public String address;
    public String memo;
    public String tags;
    public long updatedAtMillis;

    public BusinessCardEntity(
            @NonNull String id,
            String name,
            String nameEn,
            String company,
            String title,
            String department,
            String industry,
            String location,
            String phone,
            String email,
            String address,
            String memo,
            String tags,
            long updatedAtMillis
    ) {
        this.id = id;
        this.name = value(name);
        this.nameEn = value(nameEn);
        this.company = value(company);
        this.title = value(title);
        this.department = value(department);
        this.industry = value(industry);
        this.location = value(location);
        this.phone = value(phone);
        this.email = value(email);
        this.address = value(address);
        this.memo = value(memo);
        this.tags = value(tags);
        this.updatedAtMillis = updatedAtMillis;
    }

    public String searchableText() {
        // 하이픈 없이 친 전화번호("01012345678")로도 찾을 수 있게 숫자만 남긴 사본을 함께 인덱싱한다
        String phoneDigits = phone.replaceAll("[^0-9]", "");
        String source = name + " " + nameEn + " " + company + " " + title + " " + department + " "
                + industry + " " + location + " " + phone + " " + phoneDigits + " "
                + email + " " + address + " " + memo + " " + tags;
        String normalized = normalizeSearchText(source);
        return source.toLowerCase() + " " + normalized + " " + hangulBigrams(normalized);
    }

    public String ragContext() {
        return "cardId: " + id + "\n"
                + "name: " + name + "\n"
                + "company: " + company + "\n"
                + "title: " + title + "\n"
                + "department: " + department + "\n"
                + "industry: " + industry + "\n"
                + "location: " + location + "\n"
                + "phone: " + phone + "\n"
                + "email: " + email + "\n"
                + "address: " + address + "\n"
                + "memo: " + memo + "\n"
                + "tags: " + tags;
    }

    private static String value(String raw) {
        return raw == null ? "" : raw;
    }

    private static String normalizeSearchText(String raw) {
        String lower = raw == null ? "" : raw.toLowerCase();
        StringBuilder out = new StringBuilder(lower.length());
        boolean lastSpace = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '@' || c == '.' || c == '_' || c == '+' || c == '-') {
                out.append(c);
                lastSpace = false;
            } else if (!lastSpace) {
                out.append(' ');
                lastSpace = true;
            }
        }
        return out.toString().trim().replaceAll("\\s+", " ");
    }

    private static String hangulBigrams(String normalized) {
        StringBuilder out = new StringBuilder();
        String[] tokens = normalized.split("\\s+");
        for (String token : tokens) {
            if (token.length() < 3 || !containsHangul(token)) continue;
            for (int i = 0; i < token.length() - 1; i++) {
                out.append(token, i, i + 2).append(' ');
            }
        }
        return out.toString().trim();
    }

    private static boolean containsHangul(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HANGUL) return true;
        }
        return false;
    }
}
