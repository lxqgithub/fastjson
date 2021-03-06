/*
 * Copyright 1999-2017 Alibaba Group.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.fastjson.parser;

import java.io.Closeable;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.*;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.annotation.JSONType;
import com.alibaba.fastjson.serializer.CollectionCodec;
import com.alibaba.fastjson.util.FieldInfo;
import com.alibaba.fastjson.util.IOUtils;
import com.alibaba.fastjson.util.TypeUtils;

import static com.alibaba.fastjson.parser.JSONToken.*;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public abstract class JSONLexerBase implements JSONLexer, Closeable {

    protected void lexError(String key, Object... args) {
        token = ERROR;
    }

    /** 当前token含义 */
    protected int                            token;
    /** 记录当前扫描字符位置 */
    protected int                            pos;
    protected int                            features;

    /** 当前有效字符 */
    protected char                           ch;
    /** 流(或者json字符串)中当前的位置，每次读取字符会递增 */
    protected int                            bp;

    protected int                            eofPos;

    /** 字符缓冲区 */
    protected char[]                         sbuf;

    /** 字符缓冲区的索引，指向下一个可写
     *  字符的位置，也代表字符缓冲区字符数量
     */
    protected int                            sp;

    /**
     * number start position
     * 可以理解为 找到token时 token的首字符位置
     * 和bp不一样，这个不会递增，会在开始token前记录一次
     */
    protected int                            np;

    protected boolean                        hasSpecial;

    protected Calendar                       calendar           = null;
    protected TimeZone                       timeZone           = JSON.defaultTimeZone;
    protected Locale                         locale             = JSON.defaultLocale;

    public int                               matchStat          = UNKNOWN;

    private final static ThreadLocal<char[]> SBUF_LOCAL         = new ThreadLocal<char[]>();

    protected String                         stringDefaultValue = null;

    public JSONLexerBase(int features){
        this.features = features;

        if ((features & Feature.InitStringFieldAsEmpty.mask) != 0) {
            stringDefaultValue = "";
        }

        sbuf = SBUF_LOCAL.get();

        if (sbuf == null) {
            sbuf = new char[512];
        }
    }

    public final int matchStat() {
        return matchStat;
    }

    /**
     * internal method, don't invoke
     * @param token
     */
    public void setToken(int token) {
        this.token = token;
    }

    public final void nextToken() {
        /** 将字符buffer pos设置为初始0 */
        sp = 0;

        for (;;) {
            /** pos记录为流的当前位置 */
            pos = bp;

            if (ch == '/') {
                /** 如果是注释// 或者 \/* *\/ 注释，跳过注释 */
                skipComment();
                continue;
            }

            if (ch == '"') {
                /** 读取引号内的字符串 */
                scanString();
                return;
            }

            if (ch == ',') {
                /** 跳过当前，读取下一个字符 */
                next();
                token = COMMA;
                return;
            }

            if (ch >= '0' && ch <= '9') {
                /** 读取整数 */
                scanNumber();
                return;
            }

            if (ch == '-') {
                /** 读取负数 */
                scanNumber();
                return;
            }

            switch (ch) {
                /** 读取单引号后面的字符串，和scanString逻辑一致 */
                case '\'':
                    if (!isEnabled(Feature.AllowSingleQuotes)) {
                        throw new JSONException("Feature.AllowSingleQuotes is false");
                    }
                    scanStringSingleQuote();
                    return;
                case ' ':
                case '\t':
                case '\b':
                case '\f':
                case '\n':
                case '\r':
                    next();
                    break;
                case 't': // true
                    /** 读取字符true */
                    scanTrue();
                    return;
                case 'f': // false
                    /** 读取字符false */
                    scanFalse();
                    return;
                case 'n': // new,null
                    /** 读取为new或者null的token */
                    scanNullOrNew();
                    return;
                case 'T':
                case 'N': // NULL
                case 'S':
                case 'u': // undefined
                    /** 读取标识符，已经自动预读了下一个字符 */
                    scanIdent();
                    return;
                case '(':
                    /** 读取下一个字符 */
                    next();
                    token = LPAREN;
                    return;
                case ')':
                    next();
                    token = RPAREN;
                    return;
                case '[':
                    next();
                    token = LBRACKET;
                    return;
                case ']':
                    next();
                    token = RBRACKET;
                    return;
                case '{':
                    next();
                    token = LBRACE;
                    return;
                case '}':
                    next();
                    token = RBRACE;
                    return;
                case ':':
                    next();
                    token = COLON;
                    return;
                case ';':
                    next();
                    token = SEMI;
                    return;
                case '.':
                    next();
                    token = DOT;
                    return;
                case '+':
                    next();
                    scanNumber();
                    return;
                case 'x':
                    scanHex();
                    return;
                default:
                    if (isEOF()) { // JLS
                        if (token == EOF) {
                            throw new JSONException("EOF error");
                        }

                        token = EOF;
                        pos = bp = eofPos;
                    } else {
                        /** 忽略控制字符或者删除字符 */
                        if (ch <= 31 || ch == 127) {
                            next();
                            break;
                        }

                        lexError("illegal.char", String.valueOf((int) ch));
                        next();
                    }

                    return;
            }
        }

    }

    /**
     *  这个方法主要是根据期望的字符expect，判定expect对应的token
     */
    public final void nextToken(int expect) {
        /** 将字符buffer pos设置为初始0 */
        sp = 0;

        for (;;) {

            switch (expect) {
                case JSONToken.LBRACE:
                    if (ch == '{') {
                        token = JSONToken.LBRACE;
                        next();
                        return;
                    }
                    if (ch == '[') {
                        token = JSONToken.LBRACKET;
                        next();
                        return;
                    }
                    break;
                case JSONToken.COMMA:
                    if (ch == ',') {
                        token = JSONToken.COMMA;
                        next();
                        return;
                    }

                    if (ch == '}') {
                        token = JSONToken.RBRACE;
                        next();
                        return;
                    }

                    if (ch == ']') {
                        token = JSONToken.RBRACKET;
                        next();
                        return;
                    }

                    if (ch == EOI) {
                        token = JSONToken.EOF;
                        return;
                    }
                    break;
                case JSONToken.LITERAL_INT:
                    if (ch >= '0' && ch <= '9') {
                        pos = bp;
                        scanNumber();
                        return;
                    }

                    if (ch == '"') {
                        pos = bp;
                        scanString();
                        return;
                    }

                    if (ch == '[') {
                        token = JSONToken.LBRACKET;
                        next();
                        return;
                    }

                    if (ch == '{') {
                        token = JSONToken.LBRACE;
                        next();
                        return;
                    }

                    break;
                case JSONToken.LITERAL_STRING:
                    if (ch == '"') {
                        pos = bp;
                        /** 扫描字符串, pos指向字符串引号索引 */
                        scanString();
                        return;
                    }

                    if (ch >= '0' && ch <= '9') {
                        pos = bp;
                        /** 扫描数字, 前面已经分析过 */
                        scanNumber();
                        return;
                    }

                    if (ch == '[') {
                        token = JSONToken.LBRACKET;
                        next();
                        return;
                    }

                    if (ch == '{') {
                        token = JSONToken.LBRACE;
                        next();
                        return;
                    }
                    break;
                case JSONToken.LBRACKET:
                    if (ch == '[') {
                        token = JSONToken.LBRACKET;
                        next();
                        return;
                    }

                    if (ch == '{') {
                        token = JSONToken.LBRACE;
                        next();
                        return;
                    }
                    break;
                case JSONToken.RBRACKET:
                    if (ch == ']') {
                        token = JSONToken.RBRACKET;
                        next();
                        return;
                    }
                case JSONToken.EOF:
                    if (ch == EOI) {
                        token = JSONToken.EOF;
                        return;
                    }
                    break;
                case JSONToken.IDENTIFIER:
                    /** 跳过空白字符，如果是标识符_、$和字母开头，否则自动获取下一个token */
                    nextIdent();
                    return;
                default:
                    break;
            }

            /** 跳过空白字符 */
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b') {
                next();
                continue;
            }

            /** 针对其他token自动读取下一个, 比如遇到冒号：,自动下一个token */
            nextToken();
            break;
        }
    }

    public final void nextIdent() {
        /** 忽略空白字符，包括退格和换页符等等 */
        while (isWhitespace(ch)) {
            next();
        }
        if (ch == '_' || ch == '$' || Character.isLetter(ch)) {
            /** 扫描标识符，以下 _、$ 、字母开头 */
            scanIdent();
        } else {
            nextToken();
        }
    }

    public final void nextTokenWithColon() {
        nextTokenWithChar(':');
    }

    public final void nextTokenWithChar(char expect) {
        sp = 0;

        for (;;) {
            /** 如果遇到和期望expect字符相等，跳过，解析下一个token */
            if (ch == expect) {
                next();
                nextToken();
                return;
            }

            /** 忽略空白字符 */
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b') {
                next();
                continue;
            }

            throw new JSONException("not match " + expect + " - " + ch + ", info : " + this.info());
        }
    }

    public final int token() {
        return token;
    }

    public final String tokenName() {
        return JSONToken.name(token);
    }

    public final int pos() {
        return pos;
    }

    public final String stringDefaultValue() {
        return stringDefaultValue;
    }

    public final Number integerValue() throws NumberFormatException {
        long result = 0;
        boolean negative = false;
        if (np == -1) {
            np = 0;
        }
        /** np是token开始索引, sp是buffer索引，也代表buffer字符个数 */
        int i = np, max = np + sp;
        long limit;
        long multmin;
        int digit;

        char type = ' ';

        /** 探测数字类型最后一位是否带类型 */
        switch (charAt(max - 1)) {
            case 'L':
                max--;
                type = 'L';
                break;
            case 'S':
                max--;
                type = 'S';
                break;
            case 'B':
                max--;
                type = 'B';
                break;
            default:
                break;
        }

        /** 探测数字首字符是否是符号 */
        if (charAt(np) == '-') {
            negative = true;
            limit = Long.MIN_VALUE;
            i++;
        } else {
            limit = -Long.MAX_VALUE;
        }
        multmin = MULTMIN_RADIX_TEN;
        if (i < max) {
            /** 数字第一个字母转换成数字 */
            digit = charAt(i++) - '0';
            result = -digit;
        }

        /** 快速处理高精度整数，因为整数最大是10^9次方 */
        while (i < max) {
            // Accumulating negatively avoids surprises near MAX_VALUE
            digit = charAt(i++) - '0';
            /** multmin 大概10^17 */
            if (result < multmin) {
                /** numberString获取到的不包含数字后缀类型，但是包括负数符号(如果有) */
                return new BigInteger(numberString());
            }
            result *= 10;
            if (result < limit + digit) {
                return new BigInteger(numberString());
            }
            result -= digit;
        }

        if (negative) {
            /** 处理完数字 i 是指向数字最后一个字符的下一个字符,
             *  这里判断 i > np + 1 , 代表在 有效数字字符范围
             */
            if (i > np + 1) {
                /** 这里根据类型具体后缀类型做一次转换 */
                if (result >= Integer.MIN_VALUE && type != 'L') {
                    if (type == 'S') {
                        return (short) result;
                    }

                    if (type == 'B') {
                        return (byte) result;
                    }

                    return (int) result;
                }
                return result;
            } else { /* Only got "-" */
                throw new NumberFormatException(numberString());
            }
        } else {
            /** 这里是整数， 因为前面处理成负数，取反就可以了 */
            result = -result;
            /** 这里根据类型具体后缀类型做一次转换 */
            if (result <= Integer.MAX_VALUE && type != 'L') {
                if (type == 'S') {
                    return (short) result;
                }

                if (type == 'B') {
                    return (byte) result;
                }

                return (int) result;
            }
            return result;
        }
    }

    public final void nextTokenWithColon(int expect) {
        nextTokenWithChar(':');
    }

    public float floatValue() {
        /** numberString获取到的不包含数字后缀类型，但是包括负数符号(如果有) */
        String strVal = numberString();
        float floatValue = Float.parseFloat(strVal);
        /** 如果是0或者正无穷大，首字母是0-9 代表溢出 */
        if (floatValue == 0 || floatValue == Float.POSITIVE_INFINITY) {
            char c0 = strVal.charAt(0);
            if (c0 > '0' && c0 <= '9') {
                throw new JSONException("float overflow : " + strVal);
            }
        }
        return floatValue;
    }

    public double doubleValue() {
        return Double.parseDouble(numberString());
    }

    public void config(Feature feature, boolean state) {
        features = Feature.config(features, feature, state);

        if ((features & Feature.InitStringFieldAsEmpty.mask) != 0) {
            stringDefaultValue = "";
        }
    }

    public final boolean isEnabled(Feature feature) {
        return isEnabled(feature.mask);
    }

    public final boolean isEnabled(int feature) {
        return (this.features & feature) != 0;
    }

    public final boolean isEnabled(int features, int feature) {
        return (this.features & feature) != 0 || (features & feature) != 0;
    }

    public abstract String numberString();

    public abstract boolean isEOF();

    public final char getCurrent() {
        return ch;
    }

    public abstract char charAt(int index);

    // public final char next() {
    // ch = doNext();
    //// if (ch == '/' && (this.features & Feature.AllowComment.mask) != 0) {
    //// skipComment();
    //// }
    // return ch;
    // }

    public abstract char next();

    protected void skipComment() {
        /** 读下一个字符 */
        next();
        /** 连续遇到左反斜杠/ */
        if (ch == '/') {
            for (;;) {
                /** 读下一个字符 */
                next();
                if (ch == '\n') {
                    /** 如果遇到换行符，继续读取下一个字符并返回 */
                    next();
                    return;
                    /** 如果已经遇到流结束，返回 */
                } else if (ch == EOI) {
                    return;
                }
            }
            /** 遇到`/*` 注释的格式 */
        } else if (ch == '*') {
            /** 读下一个字符 */
            next();
            for (; ch != EOI;) {
                if (ch == '*') {
                    /** 如果遇到*,继续尝试读取下一个字符，看看是否是/字符 */
                    next();
                    if (ch == '/') {
                        /** 如果确实是/字符，提前预读下一个有效字符后终止 */
                        next();
                        return;
                    } else {
                        /** 遇到非/ 继续跳过度下一个字符 */
                        continue;
                    }
                }
                /** 如果没有遇到`*\` 注释格式, 继续读下一个字符 */
                next();
            }
        } else {
            /** 不符合// 或者 \/* *\/ 注释格式 */
            throw new JSONException("invalid comment");
        }
    }

    public final String scanSymbol(final SymbolTable symbolTable) {
        skipWhitespace();

        if (ch == '"') {
            return scanSymbol(symbolTable, '"');
        }

        if (ch == '\'') {
            if (!isEnabled(Feature.AllowSingleQuotes)) {
                throw new JSONException("syntax error");
            }

            return scanSymbol(symbolTable, '\'');
        }

        if (ch == '}') {
            next();
            token = JSONToken.RBRACE;
            return null;
        }

        if (ch == ',') {
            next();
            token = JSONToken.COMMA;
            return null;
        }

        if (ch == EOI) {
            token = JSONToken.EOF;
            return null;
        }

        if (!isEnabled(Feature.AllowUnQuotedFieldNames)) {
            throw new JSONException("syntax error");
        }

        return scanSymbolUnQuoted(symbolTable);
    }

    // public abstract String scanSymbol(final SymbolTable symbolTable, final char quote);

    protected abstract void arrayCopy(int srcPos, char[] dest, int destPos, int length);

    public final String scanSymbol(final SymbolTable symbolTable, final char quote) {
        int hash = 0;

        /** bp代表字符串或流当前位置，np记录的是token开始的索引位置 */
        np = bp;
        /** 设置buffer中索引为0，sp代表buffer索引，同时也代表buffer字符数 */
        sp = 0;
        boolean hasSpecial = false;
        char chLocal;
        for (;;) {
            chLocal = next();

            /** 如果遇到和字符quote相同，立即终止扫描*/
            if (chLocal == quote) {
                break;
            }

            if (chLocal == EOI) {
                throw new JSONException("unclosed.str");
            }

            /** 遇到转译字符 */
            if (chLocal == '\\') {
                if (!hasSpecial) {

                    /** 第一次遇到\认为是特殊符号 */
                    hasSpecial = true;

                    /** 如果buffer空间不够，执行2倍扩容 */
                    if (sp >= sbuf.length) {
                        int newCapcity = sbuf.length * 2;
                        if (sp > newCapcity) {
                            newCapcity = sp;
                        }
                        char[] newsbuf = new char[newCapcity];
                        System.arraycopy(sbuf, 0, newsbuf, 0, sbuf.length);
                        sbuf = newsbuf;
                    }

                    // text.getChars(np + 1, np + 1 + sp, sbuf, 0);
                    // System.arraycopy(this.buf, np + 1, sbuf, 0, sp);
                    /** 复制有效字符串到buffer中，不包括引号 */
                    arrayCopy(np + 1, sbuf, 0, sp);
                }

                chLocal = next();

                /**
                 *  处理转译特殊字符，
                 *  @see #scanString()
                 */
                switch (chLocal) {
                    case '0':
                        hash = 31 * hash + (int) chLocal;
                        putChar('\0');
                        break;
                    case '1':
                        hash = 31 * hash + (int) chLocal;
                        putChar('\1');
                        break;
                    case '2':
                        hash = 31 * hash + (int) chLocal;
                        putChar('\2');
                        break;
                    case '3':
                        hash = 31 * hash + (int) chLocal;
                        putChar('\3');
                        break;
                    case '4':
                        hash = 31 * hash + (int) chLocal;
                        putChar('\4');
                        break;
                    case '5':
                        hash = 31 * hash + (int) chLocal;
                        putChar('\5');
                        break;
                    case '6':
                        hash = 31 * hash + (int) chLocal;
                        putChar('\6');
                        break;
                    case '7':
                        hash = 31 * hash + (int) chLocal;
                        putChar('\7');
                        break;
                    case 'b': // 8
                        hash = 31 * hash + (int) '\b';
                        putChar('\b');
                        break;
                    case 't': // 9
                        hash = 31 * hash + (int) '\t';
                        putChar('\t');
                        break;
                    case 'n': // 10
                        hash = 31 * hash + (int) '\n';
                        putChar('\n');
                        break;
                    case 'v': // 11
                        hash = 31 * hash + (int) '\u000B';
                        putChar('\u000B');
                        break;
                    case 'f': // 12
                    case 'F':
                        hash = 31 * hash + (int) '\f';
                        putChar('\f');
                        break;
                    case 'r': // 13
                        hash = 31 * hash + (int) '\r';
                        putChar('\r');
                        break;
                    case '"': // 34
                        hash = 31 * hash + (int) '"';
                        putChar('"');
                        break;
                    case '\'': // 39
                        hash = 31 * hash + (int) '\'';
                        putChar('\'');
                        break;
                    case '/': // 47
                        hash = 31 * hash + (int) '/';
                        putChar('/');
                        break;
                    case '\\': // 92
                        hash = 31 * hash + (int) '\\';
                        putChar('\\');
                        break;
                    case 'x':
                        char x1 = ch = next();
                        char x2 = ch = next();

                        int x_val = digits[x1] * 16 + digits[x2];
                        char x_char = (char) x_val;
                        hash = 31 * hash + (int) x_char;
                        putChar(x_char);
                        break;
                    case 'u':
                        char c1 = chLocal = next();
                        char c2 = chLocal = next();
                        char c3 = chLocal = next();
                        char c4 = chLocal = next();
                        int val = Integer.parseInt(new String(new char[] { c1, c2, c3, c4 }), 16);
                        hash = 31 * hash + val;
                        putChar((char) val);
                        break;
                    default:
                        this.ch = chLocal;
                        throw new JSONException("unclosed.str.lit");
                }
                continue;
            }

            hash = 31 * hash + chLocal;

            if (!hasSpecial) {
                sp++;
                continue;
            }

            /** 继续处理转译字符后面的字符 */
            if (sp == sbuf.length) {
                putChar(chLocal);
            } else {
                sbuf[sp++] = chLocal;
            }
        }

        token = LITERAL_STRING;

        String value;
        /** 添加到符号表 */
        if (!hasSpecial) {
            // return this.text.substring(np + 1, np + 1 + sp).intern();
            int offset;
            if (np == -1) {
                offset = 0;
            } else {
                /** 处理np + 1 是因为np指向符号(多数是引号"), 提取引号内字符串*/
                offset = np + 1;
            }
            value = addSymbol(offset, sp, hash, symbolTable);
        } else {
            value = symbolTable.addSymbol(sbuf, 0, sp, hash);
        }

        sp = 0;
        /** 自动预读下一个字符 */
        this.next();

        return value;
    }

    public final void resetStringPosition() {
        this.sp = 0;
    }

    public String info() {
        return "";
    }

    public final String scanSymbolUnQuoted(final SymbolTable symbolTable) {
        if (token == JSONToken.ERROR && pos == 0 && bp == 1) {
            bp = 0; // adjust
        }
        final boolean[] firstIdentifierFlags = IOUtils.firstIdentifierFlags;
        final char first = ch;

        final boolean firstFlag = ch >= firstIdentifierFlags.length || firstIdentifierFlags[first];
        /** 检查是否是合法标识符，字母、_和$ 开头 */
        if (!firstFlag) {
            throw new JSONException("illegal identifier : " + ch //
                    + info());
        }

        final boolean[] identifierFlags = IOUtils.identifierFlags;

        int hash = first;

        np = bp;
        sp = 1;
        char chLocal;
        /** 循环读有效字符，主要是A-Z, a-z, _, $ */
        for (;;) {
            chLocal = next();

            if (chLocal < identifierFlags.length) {
                if (!identifierFlags[chLocal]) {
                    break;
                }
            }

            hash = 31 * hash + chLocal;

            sp++;
            continue;
        }

        this.ch = charAt(bp);
        token = JSONToken.IDENTIFIER;

        /** 当前扫描到字符是没有引号的 null */
        final int NULL_HASH = 3392903;
        if (sp == 4 && hash == NULL_HASH && charAt(np) == 'n' && charAt(np + 1) == 'u' && charAt(np + 2) == 'l'
                && charAt(np + 3) == 'l') {
            return null;
        }

        // return text.substring(np, np + sp).intern();

        if (symbolTable == null) {
            /** 求子串，针对android重新new String()，防止原来字符串驻留内存无法释放 */
            return subString(np, sp);
        }

        return this.addSymbol(np, sp, hash, symbolTable);
        // return symbolTable.addSymbol(buf, np, sp, hash);
    }

    protected abstract void copyTo(int offset, int count, char[] dest);

    public final void scanString() {
        /** 记录当前流中token的开始位置, np指向引号的索引 */
        np = bp;
        hasSpecial = false;
        char ch;
        for (;;) {

            /** 读取当前字符串的字符 */
            ch = next();

            /** 如果遇到字符串结束符"， 则结束 */
            if (ch == '\"') {
                break;
            }

            if (ch == EOI) {
                /** 如果遇到了结束符EOI，但是没有遇到流的结尾，添加EOI结束符 */
                if (!isEOF()) {
                    putChar((char) EOI);
                    continue;
                }
                throw new JSONException("unclosed string : " + ch);
            }

            /** 处理转译字符逻辑 */
            if (ch == '\\') {
                if (!hasSpecial) {
                    /** 第一次遇到\认为是特殊符号 */
                    hasSpecial = true;

                    /** 如果buffer空间不够，执行2倍扩容 */
                    if (sp >= sbuf.length) {
                        int newCapcity = sbuf.length * 2;
                        if (sp > newCapcity) {
                            newCapcity = sp;
                        }
                        char[] newsbuf = new char[newCapcity];
                        System.arraycopy(sbuf, 0, newsbuf, 0, sbuf.length);
                        sbuf = newsbuf;
                    }

                    /** 复制有效字符串到buffer中，不包括引号 */
                    copyTo(np + 1, sp, sbuf);
                    // text.getChars(np + 1, np + 1 + sp, sbuf, 0);
                    // System.arraycopy(buf, np + 1, sbuf, 0, sp);
                }

                /** 读取转译字符\下一个字符 */
                ch = next();

                /** 转换ascii字符，请参考：https://baike.baidu.com/item/ASCII/309296?fr=aladdin */
                switch (ch) {
                    case '0':
                        /** 空字符 */
                        putChar('\0');
                        break;
                    case '1':
                        /** 标题开始 */
                        putChar('\1');
                        break;
                    case '2':
                        /** 正文开始 */
                        putChar('\2');
                        break;
                    case '3':
                        /** 正文结束 */
                        putChar('\3');
                        break;
                    case '4':
                        /** 传输结束 */
                        putChar('\4');
                        break;
                    case '5':
                        /** 请求 */
                        putChar('\5');
                        break;
                    case '6':
                        /** 收到通知 */
                        putChar('\6');
                        break;
                    case '7':
                        /** 响铃 */
                        putChar('\7');
                        break;
                    case 'b': // 8
                        /** 退格 */
                        putChar('\b');
                        break;
                    case 't': // 9
                        /** 水平制表符 */
                        putChar('\t');
                        break;
                    case 'n': // 10
                        /** 换行键 */
                        putChar('\n');
                        break;
                    case 'v': // 11
                        /** 垂直制表符 */
                        putChar('\u000B');
                        break;
                    case 'f': // 12
                        /** 换页键 */
                    case 'F':
                        /** 换页键 */
                        putChar('\f');
                        break;
                    case 'r': // 13
                        /** 回车键 */
                        putChar('\r');
                        break;
                    case '"': // 34
                        /** 双引号 */
                        putChar('"');
                        break;
                    case '\'': // 39
                        /** 闭单引号 */
                        putChar('\'');
                        break;
                    case '/': // 47
                        /** 斜杠 */
                        putChar('/');
                        break;
                    case '\\': // 92
                        /** 反斜杠 */
                        putChar('\\');
                        break;
                    case 'x':
                        /** 小写字母x, 标识一个字符 */
                        char x1 = ch = next();
                        char x2 = ch = next();

                        /** x1 左移4位 + x2 */
                        int x_val = digits[x1] * 16 + digits[x2];
                        char x_char = (char) x_val;
                        putChar(x_char);
                        break;
                    case 'u':
                        /** 小写字母u, 标识一个字符 */
                        char u1 = ch = next();
                        char u2 = ch = next();
                        char u3 = ch = next();
                        char u4 = ch = next();
                        int val = Integer.parseInt(new String(new char[] { u1, u2, u3, u4 }), 16);
                        putChar((char) val);
                        break;
                    default:
                        this.ch = ch;
                        throw new JSONException("unclosed string : " + ch);
                }
                continue;
            }

            /** 没有转译字符，递增buffer字符位置 */
            if (!hasSpecial) {
                sp++;
                continue;
            }

            /** 继续读取转译字符后面的字符 */
            if (sp == sbuf.length) {
                putChar(ch);
            } else {
                sbuf[sp++] = ch;
            }
        }

        token = JSONToken.LITERAL_STRING;
        /** 自动预读下一个字符 */
        this.ch = next();
    }

    public Calendar getCalendar() {
        return this.calendar;
    }

    public TimeZone getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(TimeZone timeZone) {
        this.timeZone = timeZone;
    }

    public Locale getLocale() {
        return locale;
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    /**
     *  代码详细注释请参考 #integerValue
     *  @see #integerValue()
     */
    public final int intValue() {
        if (np == -1) {
            np = 0;
        }

        int result = 0;
        boolean negative = false;
        int i = np, max = np + sp;
        int limit;
        int digit;

        if (charAt(np) == '-') {
            negative = true;
            limit = Integer.MIN_VALUE;
            i++;
        } else {
            limit = -Integer.MAX_VALUE;
        }
        long multmin = INT_MULTMIN_RADIX_TEN;
        if (i < max) {
            digit = charAt(i++) - '0';
            result = -digit;
        }
        while (i < max) {
            // Accumulating negatively avoids surprises near MAX_VALUE
            char chLocal = charAt(i++);

            if (chLocal == 'L' || chLocal == 'S' || chLocal == 'B') {
                break;
            }

            digit = chLocal - '0';

            if (result < multmin) {
                throw new NumberFormatException(numberString());
            }
            result *= 10;
            if (result < limit + digit) {
                throw new NumberFormatException(numberString());
            }
            result -= digit;
        }

        if (negative) {
            if (i > np + 1) {
                return result;
            } else { /* Only got "-" */
                throw new NumberFormatException(numberString());
            }
        } else {
            return -result;
        }
    }

    public abstract byte[] bytesValue();

    public void close() {
        if (sbuf.length <= 1024 * 8) {
            SBUF_LOCAL.set(sbuf);
        }
        this.sbuf = null;
    }

    public final boolean isRef() {
        if (sp != 4) {
            return false;
        }

        return charAt(np + 1) == '$' //
                && charAt(np + 2) == 'r' //
                && charAt(np + 3) == 'e' //
                && charAt(np + 4) == 'f';
    }

    protected final static char[] typeFieldName = ("\"" + JSON.DEFAULT_TYPE_KEY + "\":\"").toCharArray();

    public final int scanType(String type) {
        matchStat = UNKNOWN;

        /** 从当前json串bp位置开始逐字符比较 [",@,type,", :,"] 是否匹配*/
        if (!charArrayCompare(typeFieldName)) {
            return NOT_MATCH_NAME;
        }

        int bpLocal = this.bp + typeFieldName.length;

        final int typeLength = type.length();
        for (int i = 0; i < typeLength; ++i) {
            if (type.charAt(i) != charAt(bpLocal + i)) {
                return NOT_MATCH;
            }
        }
        bpLocal += typeLength;
        /** 匹配type最后一个字符是不是引号 */
        if (charAt(bpLocal) != '"') {
            return NOT_MATCH;
        }

        this.ch = charAt(++bpLocal);

        if (ch == ',') {
            /** 预读下一个字符，标记当前token是逗号 */
            this.ch = charAt(++bpLocal);
            this.bp = bpLocal;
            token = JSONToken.COMMA;
            return VALUE;
        } else if (ch == '}') {
            ch = charAt(++bpLocal);
            if (ch == ',') {
                token = JSONToken.COMMA;
                this.ch = charAt(++bpLocal);
            } else if (ch == ']') {
                token = JSONToken.RBRACKET;
                this.ch = charAt(++bpLocal);
            } else if (ch == '}') {
                token = JSONToken.RBRACE;
                this.ch = charAt(++bpLocal);
            } else if (ch == EOI) {
                token = JSONToken.EOF;
            } else {
                return NOT_MATCH;
            }
            matchStat = END;
        }

        this.bp = bpLocal;
        return matchStat;
    }

    public final boolean matchField(char[] fieldName) {
        for (;;) {
            if (!charArrayCompare(fieldName)) {
                /** 如果当前字符和json串不匹配，忽略空格继续重试 */
                if (isWhitespace(ch)) {
                    next();
                    continue;
                }
                return false;
            } else {
                break;
            }
        }

        /** fieldName 格式是 "name":
         *  @see FieldInfo#genFieldNameChars()
         */
        bp = bp + fieldName.length;
        /** 读取字段下一个字符 */
        ch = charAt(bp);

        if (ch == '{') {
            next();
            token = JSONToken.LBRACE;
        } else if (ch == '[') {
            next();
            token = JSONToken.LBRACKET;
            /**
             *  字段值是Set类型,
             *  @see CollectionCodec#write(
             *        com.alibaba.fastjson.serializer.JSONSerializer
             *      , java.lang.Object
             *      , java.lang.Object
             *      , java.lang.reflect.Type, int)
             */
        } else if (ch == 'S' && charAt(bp + 1) == 'e' && charAt(bp + 2) == 't' && charAt(bp + 3) == '[') {
            bp += 3;
            ch = charAt(bp);
            token = JSONToken.SET;
        } else {
            /** 其他情况查找下一个token */
            nextToken();
        }

        return true;
    }

    public abstract int indexOf(char ch, int startIndex);

    public abstract String addSymbol(int offset, int len, int hash, final SymbolTable symbolTable);

    public String scanFieldString(char[] fieldName) {
        matchStat = UNKNOWN;

        /**
         *  从当前json串bp位置开始逐字符比较字段 是否匹配
         *
         *  fieldName 格式是 "name":
         *  @see FieldInfo#genFieldNameChars()
         */
        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return stringDefaultValue();
        }

        // int index = bp + fieldName.length;

        int offset = fieldName.length;
        /** 读取字段下一个字符 */
        char chLocal = charAt(bp + (offset++));

        /** json 值类型字符串一定"，否则不符合规范 */
        if (chLocal != '"') {
            matchStat = NOT_MATCH;

            return stringDefaultValue();
        }

        final String strVal;
        {
            /** startIndex指向双引号下一个字符，
             *  eg : "name":"string", startIndex指向s
             */
            int startIndex = bp + fieldName.length + 1;
            int endIndex = indexOf('"', startIndex);
            if (endIndex == -1) {
                throw new JSONException("unclosed str");
            }

            int startIndex2 = bp + fieldName.length + 1; // must re compute
            String stringVal = subString(startIndex2, endIndex - startIndex2);
            /** 包含特殊转译字符 */
            if (stringVal.indexOf('\\') != -1) {
                /**
                 * 处理场景 "value\\\"" json串值
                 */
                for (;;) {
                    int slashCount = 0;
                    for (int i = endIndex - 1; i >= 0; --i) {
                        if (charAt(i) == '\\') {
                            slashCount++;
                        } else {
                            break;
                        }
                    }
                    if (slashCount % 2 == 0) {
                        break;
                    }
                    /** 如果遇到奇数转译字符，遇到"不认为值结束，找下一个"才认为结束 */
                    endIndex = indexOf('"', endIndex + 1);
                }

                /**
                 *  ---------------------------------------------------------------------------------
                 *  | " | k | e | y | " | : | " | v | a | l | u |  e  |  \ |  \ |  \ |  " |  " |
                 *  ---------------------------------------------------------------------------------
                 *  | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 |
                 *  ---------------------------------------------------------------------------------
                 *  | bp | |   |   |   |   |   |   |   |   |    |    |    |    |    |    | endIndex |
                 *  ---------------------------------------------------------------------------------
                 *  fieldName = "key":
                 *  fieldName.length == 6, bp == 0, endIndex == 16
                 *  chars_len = 16 - (0 + 6 + 1) = 9, == value\\\"
                 */
                int chars_len = endIndex - (bp + fieldName.length + 1);
                char[] chars = sub_chars( bp + fieldName.length + 1, chars_len);

                stringVal = readString(chars, chars_len);
            }

            /** 偏移到json串字段值" 下一个字符 */
            offset += (endIndex - (bp + fieldName.length + 1) + 1);
            chLocal = charAt(bp + (offset++));
            strVal = stringVal;
        }

        if (chLocal == ',') {
            bp += offset;
            /** 读取下一个字符 */
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            return strVal;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            /** 如果字段值紧跟, 标记下次token为逗号 */
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
                /** 如果字段值紧跟] 标记下次token为右中括号 */
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
                /** 如果字段值紧跟} 标记下次token为右花括号 */
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
                /** 特殊标记结束 */
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return stringDefaultValue();
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return stringDefaultValue();
        }

        return strVal;
    }

    public String scanString(char expectNextChar) {
        matchStat = UNKNOWN;

        int offset = 0;

        char chLocal = charAt(bp + (offset++));

        /** 兼容处理null字符串 */
        if (chLocal == 'n') {
            if (charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
                offset += 3;
                chLocal = charAt(bp + (offset++));
            } else {
                matchStat = NOT_MATCH;
                return null;
            }

            if (chLocal == expectNextChar) {
                bp += offset;
                this.ch = this.charAt(bp);
                matchStat = VALUE;
                return null;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
        }

        final String strVal;
        for (;;) {
            if (chLocal == '"') {
                int startIndex = bp + offset;
                int endIndex = indexOf('"', startIndex);
                if (endIndex == -1) {
                    throw new JSONException("unclosed str");
                }

                String stringVal = subString(bp + offset, endIndex - startIndex);
                /**
                 *  处理逻辑请参考详细注释：
                 *  @see ##scanFieldString(char[])
                 */
                if (stringVal.indexOf('\\') != -1) {
                    for (; ; ) {
                        int slashCount = 0;
                        for (int i = endIndex - 1; i >= 0; --i) {
                            if (charAt(i) == '\\') {
                                slashCount++;
                            } else {
                                break;
                            }
                        }
                        if (slashCount % 2 == 0) {
                            break;
                        }
                        endIndex = indexOf('"', endIndex + 1);
                    }

                    int chars_len = endIndex - startIndex;
                    char[] chars = sub_chars(bp + 1, chars_len);

                    stringVal = readString(chars, chars_len);
                }

                offset += (endIndex - startIndex + 1);
                chLocal = charAt(bp + (offset++));
                strVal = stringVal;
                break;
            } else if (isWhitespace(chLocal)) {
                chLocal = charAt(bp + (offset++));
                continue;
            } else {
                matchStat = NOT_MATCH;

                return stringDefaultValue();
            }
        }

        for (;;) {
            /** 如果遇到和期望字符认为结束符 */
            if (chLocal == expectNextChar) {
                bp += offset;
                /** 预读下一个字符 */
                this.ch = charAt(bp);
                matchStat = VALUE;
                return strVal;
            } else if (isWhitespace(chLocal)) {
                chLocal = charAt(bp + (offset++));
                continue;
            } else {
                matchStat = NOT_MATCH;
                return strVal;
            }
        }
    }

    public long scanFieldSymbol(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return 0;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        if (chLocal != '"') {
            matchStat = NOT_MATCH;
            return 0;
        }

        long hash = 0xcbf29ce484222325L;
        for (;;) {
            chLocal = charAt(bp + (offset++));
            if (chLocal == '\"') {
                chLocal = charAt(bp + (offset++));
                break;
            }

            hash ^= chLocal;
            hash *= 0x100000001b3L;

            if (chLocal == '\\') {
                matchStat = NOT_MATCH;
                return 0;
            }
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            return hash;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return 0;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        return hash;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Enum<?> scanEnum(Class<?> enumClass, final SymbolTable symbolTable, char serperator) {
        String name = scanSymbolWithSeperator(symbolTable, serperator);
        if (name == null) {
            return null;
        }
        return Enum.valueOf((Class<? extends Enum>) enumClass, name);
    }

    public String scanSymbolWithSeperator(final SymbolTable symbolTable, char serperator) {
        matchStat = UNKNOWN;

        int offset = 0;
        /** 读取第一个token字符 */
        char chLocal = charAt(bp + (offset++));

        /** 兼容处理null 标识符 */
        if (chLocal == 'n') {
            if (charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
                offset += 3;
                chLocal = charAt(bp + (offset++));
            } else {
                matchStat = NOT_MATCH;
                return null;
            }

            if (chLocal == serperator) {
                bp += offset;
                this.ch = this.charAt(bp);
                matchStat = VALUE;
                return null;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
        }

        if (chLocal != '"') {
            matchStat = NOT_MATCH;
            return null;
        }

        String strVal;
        // int start = index;
        int hash = 0;
        for (;;) {
            chLocal = charAt(bp + (offset++));
            if (chLocal == '\"') {
                // bp = index;
                // this.ch = chLocal = charAt(bp);
                int start = bp + 0 + 1;
                int len = bp + offset - start - 1;
                /** 扫描到结束字符，加到符号表中，
                 *  len = offset -2 , 不是更简洁吗？
                 */
                strVal = addSymbol(start, len, hash, symbolTable);
                chLocal = charAt(bp + (offset++));
                break;
            }

            hash = 31 * hash + chLocal;

            /** 不允许有转译字符 */
            if (chLocal == '\\') {
                matchStat = NOT_MATCH;
                return null;
            }
        }

        for (;;) {
            /** 检查字符是否是期望的结束字符serperator */
            if (chLocal == serperator) {
                bp += offset;
                this.ch = this.charAt(bp);
                matchStat = VALUE;
                return strVal;
            } else {
                /** 忽略任意多个空白字符 */
                if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + (offset++));
                    continue;
                }

                matchStat = NOT_MATCH;
                return strVal;
            }
        }
    }

    public Collection<String> newCollectionByType(Class<?> type){
        if (type.isAssignableFrom(HashSet.class)) {
            HashSet<String> list = new HashSet<String>();
            return list;
        } else if (type.isAssignableFrom(ArrayList.class)) {
            ArrayList<String> list2 = new ArrayList<String>();
            return list2;
        } else {
            try {
                Collection<String> list = (Collection<String>) type.newInstance();
                return list;
            } catch (Exception e) {
                throw new JSONException(e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public Collection<String> scanFieldStringArray(char[] fieldName, Class<?> type) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return null;
        }

        Collection<String> list = newCollectionByType(type);

//        if (type.isAssignableFrom(HashSet.class)) {
//            list = new HashSet<String>();
//        } else if (type.isAssignableFrom(ArrayList.class)) {
//            list = new ArrayList<String>();
//        } else {
//            try {
//                list = (Collection<String>) type.newInstance();
//            } catch (Exception e) {
//                throw new JSONException(e.getMessage(), e);
//            }
//        }

        // int index = bp + fieldName.length;

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        if (chLocal != '[') {
            matchStat = NOT_MATCH;
            return null;
        }

        chLocal = charAt(bp + (offset++));

        for (;;) {
            // int start = index;
            if (chLocal == '"') {
                int startIndex = bp + offset;
                int endIndex = indexOf('"', startIndex);
                if (endIndex == -1) {
                    throw new JSONException("unclosed str");
                }

                int startIndex2 = bp + offset; // must re compute
                String stringVal = subString(startIndex2, endIndex - startIndex2);
                if (stringVal.indexOf('\\') != -1) {
                    for (;;) {
                        int slashCount = 0;
                        for (int i = endIndex - 1; i >= 0; --i) {
                            if (charAt(i) == '\\') {
                                slashCount++;
                            } else {
                                break;
                            }
                        }
                        if (slashCount % 2 == 0) {
                            break;
                        }
                        endIndex = indexOf('"', endIndex + 1);
                    }

                    int chars_len = endIndex - (bp + offset);
                    char[] chars = sub_chars(bp + offset, chars_len);

                    stringVal = readString(chars, chars_len);
                }

                offset += (endIndex - (bp + offset) + 1);
                chLocal = charAt(bp + (offset++));

                list.add(stringVal);
            } else if (chLocal == 'n' //
                    && charAt(bp + offset) == 'u' //
                    && charAt(bp + offset + 1) == 'l' //
                    && charAt(bp + offset + 2) == 'l') {
                offset += 3;
                chLocal = charAt(bp + (offset++));
                list.add(null);
            } else if (chLocal == ']' && list.size() == 0) {
                chLocal = charAt(bp + (offset++));
                break;
            } else {
                throw new JSONException("illega str");
            }

            if (chLocal == ',') {
                chLocal = charAt(bp + (offset++));
                continue;
            }

            if (chLocal == ']') {
                chLocal = charAt(bp + (offset++));
                break;
            }

            matchStat = NOT_MATCH;
            return null;
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            return list;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                bp += (offset - 1);
                token = JSONToken.EOF;
                this.ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return list;
    }

    public void scanStringArray(Collection<String> list, char seperator) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));

        if (chLocal == 'n'
                && charAt(bp + offset) == 'u'
                && charAt(bp + offset + 1) == 'l'
                && charAt(bp + offset + 2) == 'l'
                && charAt(bp + offset + 3) == seperator
                ) {
            bp += 5;
            ch = charAt(bp);
            matchStat = VALUE_NULL;
            return;
        }

        if (chLocal != '[') {
            matchStat = NOT_MATCH;
            return;
        }

        chLocal = charAt(bp + (offset++));

        for (;;) {
            if (chLocal == 'n' //
                    && charAt(bp + offset) == 'u' //
                    && charAt(bp + offset + 1) == 'l' //
                    && charAt(bp + offset + 2) == 'l') {
                offset += 3;
                chLocal = charAt(bp + (offset++));
                list.add(null);
            } else if (chLocal == ']' && list.size() == 0) {
                chLocal = charAt(bp + (offset++));
                break;
            } else if (chLocal != '"') {
                matchStat = NOT_MATCH;
                return;
            } else {
                int startIndex = bp + offset;
                int endIndex = indexOf('"', startIndex);
                if (endIndex == -1) {
                    throw new JSONException("unclosed str");
                }

                String stringVal = subString(bp + offset, endIndex - startIndex);
                if (stringVal.indexOf('\\') != -1) {
                    for (;;) {
                        int slashCount = 0;
                        for (int i = endIndex - 1; i >= 0; --i) {
                            if (charAt(i) == '\\') {
                                slashCount++;
                            } else {
                                break;
                            }
                        }
                        if (slashCount % 2 == 0) {
                            break;
                        }
                        endIndex = indexOf('"', endIndex + 1);
                    }

                    int chars_len = endIndex - startIndex;
                    char[] chars = sub_chars(bp + offset, chars_len);

                    stringVal = readString(chars, chars_len);
                }

                offset += (endIndex - (bp + offset) + 1);
                chLocal = charAt(bp + (offset++));
                list.add(stringVal);
            }

            if (chLocal == ',') {
                chLocal = charAt(bp + (offset++));
                continue;
            }

            if (chLocal == ']') {
                chLocal = charAt(bp + (offset++));
                break;
            }

            matchStat = NOT_MATCH;
            return;
        }

        if (chLocal == seperator) {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            return;
        } else {
            matchStat = NOT_MATCH;
            return;
        }
    }

    public int scanFieldInt(char[] fieldName) {
        matchStat = UNKNOWN;

        /** 属性不匹配，忽略 */
        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return 0;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        final boolean negative = chLocal == '-';
        if (negative) {
            /** 如果是负数，读取第一个数字字符 */
            chLocal = charAt(bp + (offset++));
        }

        int value;
        if (chLocal >= '0' && chLocal <= '9') {
            /** 转换成数字 */
            value = chLocal - '0';
            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    value = value * 10 + (chLocal - '0');
                } else if (chLocal == '.') {
                    /** 数字后面有点，不符合整数，标记不匹配 */
                    matchStat = NOT_MATCH;
                    return 0;
                } else {
                    break;
                }
            }
            /** value < 0 代表整数值溢出了,
             *  11 + 3 代表了最小负数加了引号(占用2), 剩余
             *  占用1 是因为读完最后一位数字，offset++ 递增了1
             */
            if (value < 0
                    || offset > 11 + 3 + fieldName.length) {
                if (value != Integer.MIN_VALUE
                        || offset != 17
                        || !negative) {
                    matchStat = NOT_MATCH;
                    return 0;
                }
            }
        } else {
            /** 非数字代表不匹配 */
            matchStat = NOT_MATCH;
            return 0;
        }

        /** 如果遇到逗号，认为结束 */
        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return negative ? -value : value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return 0;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        return negative ? -value : value;
    }

    public final int[] scanFieldIntArray(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return null;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        if (chLocal != '[') {
            matchStat = NOT_MATCH_NAME;
            return null;
        }
        chLocal = charAt(bp + (offset++));

        int[] array = new int[16];
        int arrayIndex = 0;

        if (chLocal == ']') {
            chLocal = charAt(bp + (offset++));
        } else {
            for (;;) {
                boolean nagative = false;
                if (chLocal == '-') {
                    chLocal = charAt(bp + (offset++));
                    nagative = true;
                }
                if (chLocal >= '0' && chLocal <= '9') {
                    int value = chLocal - '0';
                    for (; ; ) {
                        chLocal = charAt(bp + (offset++));

                        if (chLocal >= '0' && chLocal <= '9') {
                            value = value * 10 + (chLocal - '0');
                        } else {
                            break;
                        }
                    }

                    if (arrayIndex >= array.length) {
                        int[] tmp = new int[array.length * 3 / 2];
                        System.arraycopy(array, 0, tmp, 0, arrayIndex);
                        array = tmp;
                    }
                    array[arrayIndex++] = nagative ? -value : value;

                    if (chLocal == ',') {
                        chLocal = charAt(bp + (offset++));
                    } else if (chLocal == ']') {
                        chLocal = charAt(bp + (offset++));
                        break;
                    }
                } else {
                    matchStat = NOT_MATCH;
                    return null;
                }
            }
        }


        if (arrayIndex != array.length) {
            int[] tmp = new int[arrayIndex];
            System.arraycopy(array, 0, tmp, 0, arrayIndex);
            array = tmp;
        }

        if (chLocal == ',') {
            bp += (offset - 1);
            this.next();
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return array;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == EOI) {
                bp += (offset - 1);
                token = JSONToken.EOF;
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return array;
    }

    public boolean scanBoolean(char expectNext) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));

        boolean value = false;
        /** 如果匹配到"true" 返回 true */
        if (chLocal == 't') {
            if (charAt(bp + offset) == 'r'
                    && charAt(bp + offset + 1) == 'u'
                    && charAt(bp + offset + 2) == 'e') {
                offset += 3;
                chLocal = charAt(bp + (offset++));
                value = true;
            } else {
                matchStat = NOT_MATCH;
                return false;
            }
            /** 如果匹配到"false" 返回 false */
        } else if (chLocal == 'f') {
            if (charAt(bp + offset) == 'a' //
                    && charAt(bp + offset + 1) == 'l' //
                    && charAt(bp + offset + 2) == 's' //
                    && charAt(bp + offset + 3) == 'e') {
                offset += 4;
                chLocal = charAt(bp + (offset++));
                value = false;
            } else {
                matchStat = NOT_MATCH;
                return false;
            }
        } else if (chLocal == '1') {
            chLocal = charAt(bp + (offset++));
            value = true;
        } else if (chLocal == '0') {
            chLocal = charAt(bp + (offset++));
            value = false;
        }

        for (;;) {
            /** 如果匹配到期望字符串，结束 */
            if (chLocal == expectNext) {
                bp += offset;
                this.ch = this.charAt(bp);
                matchStat = VALUE;
                return value;
            } else {
                if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + (offset++));
                    continue;
                }
                matchStat = NOT_MATCH;
                return value;
            }
        }
    }

    public int scanInt(char expectNext) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));

        /** 取整数第一个字符判断是否是引号 */
        final boolean quote = chLocal == '"';
        if (quote) {
            /** 如果是双引号，取第一个数字字符 */
            chLocal = charAt(bp + (offset++));
        }

        final boolean negative = chLocal == '-';
        if (negative) {
            /** 如果是负数，继续取下一个字符 */
            chLocal = charAt(bp + (offset++));
        }

        int value;
        /** 是数字类型 */
        if (chLocal >= '0' && chLocal <= '9') {
            value = chLocal - '0';
            for (;;) {
                /** 循环将字符转换成数字 */
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    value = value * 10 + (chLocal - '0');
                } else if (chLocal == '.') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else {
                    break;
                }
            }
            if (value < 0) {
                matchStat = NOT_MATCH;
                return 0;
            }
        } else if (chLocal == 'n' && charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
            /** 匹配到null */
            matchStat = VALUE_NULL;
            value = 0;
            offset += 3;
            /** 读取null后面的一个字符 */
            chLocal = charAt(bp + offset++);

            if (quote && chLocal == '"') {
                chLocal = charAt(bp + offset++);
            }

            for (;;) {
                /** 如果读取null后面有逗号，认为结束 */
                if (chLocal == ',') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.COMMA;
                    return value;
                } else if (chLocal == ']') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.RBRACKET;
                    return value;
                    /** 忽略空白字符 */
                } else if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + offset++);
                    continue;
                }
                break;
            }
            matchStat = NOT_MATCH;
            return 0;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        for (;;) {
            /** 根据期望字符用于结束匹配 */
            if (chLocal == expectNext) {
                bp += offset;
                this.ch = this.charAt(bp);
                matchStat = VALUE;
                token = JSONToken.COMMA;
                return negative ? -value : value;
            } else {
                /** 忽略空白字符 */
                if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + (offset++));
                    continue;
                }
                matchStat = NOT_MATCH;
                return negative ? -value : value;
            }
        }
    }

    public boolean scanFieldBoolean(char[] fieldName) {
        matchStat = UNKNOWN;

        /**
         *  从当前json串bp位置开始逐字符比较字段 是否匹配
         *
         *  fieldName 格式是 "name":
         *  @see FieldInfo#genFieldNameChars()
         */
        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return false;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        boolean value;
        /** 匹配true */
        if (chLocal == 't') {
            if (charAt(bp + (offset++)) != 'r') {
                matchStat = NOT_MATCH;
                return false;
            }
            if (charAt(bp + (offset++)) != 'u') {
                matchStat = NOT_MATCH;
                return false;
            }
            if (charAt(bp + (offset++)) != 'e') {
                matchStat = NOT_MATCH;
                return false;
            }

            value = true;
            /** 匹配false */
        } else if (chLocal == 'f') {
            if (charAt(bp + (offset++)) != 'a') {
                matchStat = NOT_MATCH;
                return false;
            }
            if (charAt(bp + (offset++)) != 'l') {
                matchStat = NOT_MATCH;
                return false;
            }
            if (charAt(bp + (offset++)) != 's') {
                matchStat = NOT_MATCH;
                return false;
            }
            if (charAt(bp + (offset++)) != 'e') {
                matchStat = NOT_MATCH;
                return false;
            }

            value = false;
        } else {
            /** 如果不是true或者false 直接返回 */
            matchStat = NOT_MATCH;
            return false;
        }

        chLocal = charAt(bp + offset++);
        /** 如果true或者false后面跟着一个逗号 */
        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;

            return value;
        }

        /** 如果true或者false后面跟着一个闭合花括号 */
        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            /** 如果闭合花括号紧跟着逗号、中括号、花括号，匹配有效 */
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return false;
            }
            matchStat = END;
        } else {
            /** boolean后面没有跟 逗号或者 } ,认为不合法 */
            matchStat = NOT_MATCH;
            return false;
        }

        return value;
    }

    public long scanFieldLong(char[] fieldName) {
        matchStat = UNKNOWN;

        /**
         *  从当前json串bp位置开始逐字符比较字段 是否匹配
         *
         *  fieldName 格式是 "name":
         *  @see FieldInfo#genFieldNameChars()
         */
        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return 0;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        boolean negative = false;
        if (chLocal == '-') {
            /** 有符号，标识是负数 */
            chLocal = charAt(bp + (offset++));
            negative = true;
        }

        long value;
        if (chLocal >= '0' && chLocal <= '9') {
            value = chLocal - '0';
            for (;;) {
                /** 循环将字符转换成数字 */
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    value = value * 10 + (chLocal - '0');
                    /** 如果数字带标点符号，认为不是合法整数，匹配失败 */
                } else if (chLocal == '.') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else {
                    break;
                }
            }

            /** 如果偏移量超过最大long的21位，是无效数字 */
            boolean valid = offset - fieldName.length < 21
                    && (value >= 0 || (value == -9223372036854775808L && negative));
            if (!valid) {
                matchStat = NOT_MATCH;
                return 0;
            }
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == ',') {
            /** 如果数字后面跟着逗号，结束 并预读下一个字符 */
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return negative ? -value : value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return 0;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        return negative ? -value : value;
    }

    public long scanLong(char expectNextChar) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));
        final boolean quote = chLocal == '"';
        if (quote) {
            /** 有引号，继续读下一个字符 */
            chLocal = charAt(bp + (offset++));
        }

        final boolean negative = chLocal == '-';
        if (negative) {
            /** 有符号，标识是负数 */
            chLocal = charAt(bp + (offset++));
        }

        long value;
        /** 循环将字符转换成数字 */
        if (chLocal >= '0' && chLocal <= '9') {
            value = chLocal - '0';
            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    value = value * 10 + (chLocal - '0');
                } else if (chLocal == '.') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else {
                    break;
                }
            }
            /** 如果偏移量超过最大long的21位，是无效数字 */
            boolean valid = value >= 0 || (value == -9223372036854775808L && negative);
            if (!valid) {
                String val = subString(bp, offset - 1);
                throw new NumberFormatException(val);
            }
        } else if (chLocal == 'n' && charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
            matchStat = VALUE_NULL;
            value = 0;
            offset += 3;
            chLocal = charAt(bp + offset++);

            if (quote && chLocal == '"') {
                chLocal = charAt(bp + offset++);
            }

            for (;;) {
                if (chLocal == ',') {
                    /** 如果是null, 紧跟着逗号，认为结束匹配 */
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.COMMA;
                    return value;
                } else if (chLocal == ']') {
                    /** 如果是null, 紧跟着逗号], 认为结束匹配 */
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.RBRACKET;
                    return value;
                } else if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + offset++);
                    continue;
                }
                break;
            }
            matchStat = NOT_MATCH;
            return 0;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (quote) {
            if (chLocal != '"') {
                matchStat = NOT_MATCH;
                return 0;
            } else {
                chLocal = charAt(bp + (offset++));
            }
        }

        for (;;) {
            if (chLocal == expectNextChar) {
                bp += offset;
                this.ch = this.charAt(bp);
                matchStat = VALUE;
                token = JSONToken.COMMA;
                return negative ? -value : value;
            } else {
                if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + (offset++));
                    continue;
                }

                matchStat = NOT_MATCH;
                return value;
            }
        }
    }

    public final float scanFieldFloat(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return 0;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        final boolean quote = chLocal == '"';
        if (quote) {
            chLocal = charAt(bp + (offset++));
        }

        boolean negative = chLocal == '-';
        if (negative) {
            chLocal = charAt(bp + (offset++));
        }

        float value;
        if (chLocal >= '0' && chLocal <= '9') {
            int intVal = chLocal - '0';
            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    intVal = intVal * 10 + (chLocal - '0');
                    continue;
                } else {
                    /** 如果遇到非数字字符终止 */
                    break;
                }
            }

            int power = 1;
            boolean small = (chLocal == '.');
            if (small) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    /** 将小数点后面数字转换成int类型数字 */
                    intVal = intVal * 10 + (chLocal - '0');
                    power = 10;
                    for (;;) {
                        chLocal = charAt(bp + (offset++));
                        if (chLocal >= '0' && chLocal <= '9') {
                            /** 依次读取数字并转化int，记录小数点的数量级 */
                            intVal = intVal * 10 + (chLocal - '0');
                            power *= 10;
                            continue;
                        } else {
                            break;
                        }
                    }
                } else {
                    matchStat = NOT_MATCH;
                    return 0;
                }
            }

            boolean exp = chLocal == 'e' || chLocal == 'E';
            if (exp) {
                /** 处理科学计数法 */
                chLocal = charAt(bp + (offset++));
                if (chLocal == '+' || chLocal == '-') {
                    chLocal = charAt(bp + (offset++));
                }
                for (;;) {
                    if (chLocal >= '0' && chLocal <= '9') {
                        chLocal = charAt(bp + (offset++));
                    } else {
                        break;
                    }
                }
            }

            int start, count;
            if (quote) {
                if (chLocal != '"') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else {
                    /** 遇到浮点数最后一个引号，预读下一个 */
                    chLocal = charAt(bp + (offset++));
                }

                /**
                 *  ----------------------------------------------------------------------------------------
                 *  | { | " | k | e | y | " | : | " | 7 | 0 | 0 | 8   |  .  |  5 |  5 |  5 |  5 |  " |  }
                 *  ----------------------------------------------------------------------------------------
                 *  | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 |  18
                 *  ----------------------------------------------------------------------------------------
                 *  |  | bp |  |   |   |   |   | |start|   |    |    |    |    |    |    |    |    | offset
                 *  ----------------------------------------------------------------------------------------
                 *  fieldName = "key":
                 *  fieldName.length == 6, bp == 0, offset == 17
                 *  start代表指向浮点第一个数字或者-号,
                 *  @see com.alibaba.json.bvt.parser.deser.BooleanFieldDeserializerTest#test_2()
                 */
                start = bp + fieldName.length + 1;
                count = bp + offset - start - 2;
            } else {
                start = bp + fieldName.length;
                count = bp + offset - start - 1;
            }

            if (!exp && count < 20) {
                value = ((float) intVal) / power;
                if (negative) {
                    value = -value;
                }
            } else {
                String text = this.subString(start, count);
                value = Float.parseFloat(text);
            }
        } else if (chLocal == 'n' && charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
            matchStat = VALUE_NULL;
            value = 0;
            offset += 3;
            chLocal = charAt(bp + offset++);

            if (quote && chLocal == '"') {
                chLocal = charAt(bp + offset++);
            }

            for (;;) {
                if (chLocal == ',') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.COMMA;
                    return value;
                } else if (chLocal == '}') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.RBRACE;
                    return value;
                } else if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + offset++);
                    continue;
                }
                break;
            }
            matchStat = NOT_MATCH;
            return 0;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                bp += (offset - 1);
                token = JSONToken.EOF;
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return 0;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        return value;
    }

    /***
     * @see #scanFieldFloat(char[])
     */
    public final float scanFloat(char seperator) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));
        final boolean quote = chLocal == '"';
        if (quote) {
            chLocal = charAt(bp + (offset++));
        }

        boolean negative = chLocal == '-';
        if (negative) {
            chLocal = charAt(bp + (offset++));
        }

        float value;
        if (chLocal >= '0' && chLocal <= '9') {
            long intVal = chLocal - '0';
            for (; ; ) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    intVal = intVal * 10 + (chLocal - '0');
                    continue;
                } else {
                    break;
                }
            }

            long power = 1;
            boolean small = (chLocal == '.');
            if (small) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    intVal = intVal * 10 + (chLocal - '0');
                    power = 10;
                    for (; ; ) {
                        chLocal = charAt(bp + (offset++));
                        if (chLocal >= '0' && chLocal <= '9') {
                            intVal = intVal * 10 + (chLocal - '0');
                            power *= 10;
                            continue;
                        } else {
                            break;
                        }
                    }
                } else {
                    matchStat = NOT_MATCH;
                    return 0;
                }
            }

            boolean exp = chLocal == 'e' || chLocal == 'E';
            if (exp) {
                chLocal = charAt(bp + (offset++));
                if (chLocal == '+' || chLocal == '-') {
                    chLocal = charAt(bp + (offset++));
                }
                for (; ; ) {
                    if (chLocal >= '0' && chLocal <= '9') {
                        chLocal = charAt(bp + (offset++));
                    } else {
                        break;
                    }
                }
            }
//            int start, count;
//            if (quote) {
//                if (chLocal != '"') {
//                    matchStat = NOT_MATCH;
//                    return 0;
//                } else {
//                    chLocal = charAt(bp + (offset++));
//                }
//                start = bp + 1;
//                count = bp + offset - start - 2;
//            } else {
//                start = bp;
//                count = bp + offset - start - 1;
//            }
//            String text = this.subString(start, count);
//            value = Float.parseFloat(text);
            int start, count;
            if (quote) {
                if (chLocal != '"') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else {
                    chLocal = charAt(bp + (offset++));
                }
                start = bp + 1;
                count = bp + offset - start - 2;
            } else {
                start = bp;
                count = bp + offset - start - 1;
            }

            if (!exp && count < 20) {
                value = ((float) intVal) / power;
                if (negative) {
                    value = -value;
                }
            } else {
                String text = this.subString(start, count);
                value = Float.parseFloat(text);
            }
        } else if (chLocal == 'n' && charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
            matchStat = VALUE_NULL;
            value = 0;
            offset += 3;
            chLocal = charAt(bp + offset++);

            if (quote && chLocal == '"') {
                chLocal = charAt(bp + offset++);
            }

            for (;;) {
                if (chLocal == ',') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.COMMA;
                    return value;
                } else if (chLocal == ']') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.RBRACKET;
                    return value;
                } else if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + offset++);
                    continue;
                }
                break;
            }
            matchStat = NOT_MATCH;
            return 0;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == seperator) {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        } else {
            matchStat = NOT_MATCH;
            return value;
        }
    }

    public double scanDouble(char seperator) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));
        final boolean quote = chLocal == '"';
        if (quote) {
            /** 如果包含引号，预读下一个字符 */
            chLocal = charAt(bp + (offset++));
        }

        boolean negative = chLocal == '-';
        if (negative) {
            /** 如果引号紧跟着-号，预读下一个字符 */
            chLocal = charAt(bp + (offset++));
        }

        double value;
        if (chLocal >= '0' && chLocal <= '9') {
            long intVal = chLocal - '0';
            for (; ; ) {
                /** 读取double数字类型并转换成数字 */
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    intVal = intVal * 10 + (chLocal - '0');
                    continue;
                } else {
                    break;
                }
            }

            long power = 1;
            boolean small = (chLocal == '.');
            if (small) {
                /** 读取小数点后面的数字类型并转换，记录小数点的位数 */
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    intVal = intVal * 10 + (chLocal - '0');
                    power = 10;
                    for (; ; ) {
                        chLocal = charAt(bp + (offset++));
                        if (chLocal >= '0' && chLocal <= '9') {
                            intVal = intVal * 10 + (chLocal - '0');
                            power *= 10;
                            continue;
                        } else {
                            break;
                        }
                    }
                } else {
                    matchStat = NOT_MATCH;
                    return 0;
                }
            }

            /** 处理科学计数法 */
            boolean exp = chLocal == 'e' || chLocal == 'E';
            if (exp) {
                chLocal = charAt(bp + (offset++));
                if (chLocal == '+' || chLocal == '-') {
                    chLocal = charAt(bp + (offset++));
                }
                for (; ; ) {
                    if (chLocal >= '0' && chLocal <= '9') {
                        chLocal = charAt(bp + (offset++));
                    } else {
                        break;
                    }
                }
            }

            int start, count;
            if (quote) {
                if (chLocal != '"') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else {
                    chLocal = charAt(bp + (offset++));
                }
                start = bp + 1;
                count = bp + offset - start - 2;
            } else {
                start = bp;
                count = bp + offset - start - 1;
            }

            if (!exp && count < 20) {
                value = ((double) intVal) / power;
                if (negative) {
                    value = -value;
                }
            } else {
                String text = this.subString(start, count);
                value = Double.parseDouble(text);
            }
        } else if (chLocal == 'n' && charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
            matchStat = VALUE_NULL;
            value = 0;
            offset += 3;
            chLocal = charAt(bp + offset++);

            if (quote && chLocal == '"') {
                chLocal = charAt(bp + offset++);
            }

            for (;;) {
                if (chLocal == ',') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.COMMA;
                    return value;
                } else if (chLocal == ']') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.RBRACKET;
                    return value;
                } else if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + offset++);
                    continue;
                }
                break;
            }
            matchStat = NOT_MATCH;
            return 0;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == seperator) {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        } else {
            matchStat = NOT_MATCH;
            return value;
        }
    }

    public BigDecimal scanDecimal(char seperator) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));
        /** 取第一个字符并判断是否是引号 */
        final boolean quote = chLocal == '"';
        if (quote) {
            chLocal = charAt(bp + (offset++));
        }

        boolean negative = chLocal == '-';
        if (negative) {
            /** 取引号的字符后面的-符号 */
            chLocal = charAt(bp + (offset++));
        }

        BigDecimal value;
        if (chLocal >= '0' && chLocal <= '9') {
            for (;;) {
                /** 如果是数字字符，递增偏移量继续查找 */
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    continue;
                } else {
                    break;
                }
            }

            boolean small = (chLocal == '.');
            if (small) {
                /** 如果是小数点, 读取后面字符尝试判断是否是数字字符 */
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    for (;;) {
                        chLocal = charAt(bp + (offset++));
                        if (chLocal >= '0' && chLocal <= '9') {
                            continue;
                        } else {
                            break;
                        }
                    }
                } else {
                    matchStat = NOT_MATCH;
                    return null;
                }
            }

            boolean exp = chLocal == 'e' || chLocal == 'E';
            if (exp) {
                chLocal = charAt(bp + (offset++));
                if (chLocal == '+' || chLocal == '-') {
                    chLocal = charAt(bp + (offset++));
                }
                for (;;) {
                    if (chLocal >= '0' && chLocal <= '9') {
                        chLocal = charAt(bp + (offset++));
                    } else {
                        break;
                    }
                }
            }

            int start, count;
            if (quote) {
                if (chLocal != '"') {
                    matchStat = NOT_MATCH;
                    return null;
                } else {
                    chLocal = charAt(bp + (offset++));
                }
                start = bp + 1;
                count = bp + offset - start - 2;
            } else {
                start = bp;
                count = bp + offset - start - 1;
            }

            char[] chars = this.sub_chars(start, count);
            /** 直接读取字符串，用BigDecimal构造函数初始化 */
            value = new BigDecimal(chars);
        } else if (chLocal == 'n' && charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
            matchStat = VALUE_NULL;
            value = null;
            offset += 3;
            chLocal = charAt(bp + offset++);

            if (quote && chLocal == '"') {
                chLocal = charAt(bp + offset++);
            }

            for (;;) {
                if (chLocal == ',') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.COMMA;
                    return value;
                } else if (chLocal == '}') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.RBRACE;
                    return value;
                } else if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + offset++);
                    continue;
                }
                break;
            }
            matchStat = NOT_MATCH;
            return null;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        }

        if (chLocal == ']') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return value;
    }

    public final float[] scanFieldFloatArray(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return null;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));
        if (chLocal != '[') {
            matchStat = NOT_MATCH_NAME;
            return null;
        }
        chLocal = charAt(bp + (offset++));

        float[] array = new float[16];
        int arrayIndex = 0;

        for (;;) {
            int start = bp + offset - 1;

            boolean negative = chLocal == '-';
            if (negative) {
                chLocal = charAt(bp + (offset++));
            }

            if (chLocal >= '0' && chLocal <= '9') {
                int intVal = chLocal - '0';
                for (; ; ) {
                    chLocal = charAt(bp + (offset++));
                    if (chLocal >= '0' && chLocal <= '9') {
                        intVal = intVal * 10 + (chLocal - '0');
                        continue;
                    } else {
                        break;
                    }
                }

                int power = 1;
                boolean small = (chLocal == '.');
                if (small) {
                    chLocal = charAt(bp + (offset++));
                    power = 10;
                    if (chLocal >= '0' && chLocal <= '9') {
                        intVal = intVal * 10 + (chLocal - '0');
                        for (; ; ) {
                            chLocal = charAt(bp + (offset++));

                            if (chLocal >= '0' && chLocal <= '9') {
                                intVal = intVal * 10 + (chLocal - '0');
                                power *= 10;
                                continue;
                            } else {
                                break;
                            }
                        }
                    } else {
                        matchStat = NOT_MATCH;
                        return null;
                    }
                }

                boolean exp = chLocal == 'e' || chLocal == 'E';
                if (exp) {
                    chLocal = charAt(bp + (offset++));
                    if (chLocal == '+' || chLocal == '-') {
                        chLocal = charAt(bp + (offset++));
                    }
                    for (;;) {
                        if (chLocal >= '0' && chLocal <= '9') {
                            chLocal = charAt(bp + (offset++));
                        } else {
                            break;
                        }
                    }
                }

                int count = bp + offset - start - 1;

                float value;
                if (!exp && count < 10) {
                    value = ((float) intVal) / power;
                    if (negative) {
                        value = -value;
                    }
                } else {
                    String text = this.subString(start, count);
                    value = Float.parseFloat(text);
                }

                if (arrayIndex >= array.length) {
                    float[] tmp = new float[array.length * 3 / 2];
                    System.arraycopy(array, 0, tmp, 0, arrayIndex);
                    array = tmp;
                }
                array[arrayIndex++] = value;

                if (chLocal == ',') {
                    chLocal = charAt(bp + (offset++));
                } else if (chLocal == ']') {
                    chLocal = charAt(bp + (offset++));
                    break;
                }
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
        }


        if (arrayIndex != array.length) {
            float[] tmp = new float[arrayIndex];
            System.arraycopy(array, 0, tmp, 0, arrayIndex);
            array = tmp;
        }

        if (chLocal == ',') {
            bp += (offset - 1);
            this.next();
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return array;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == EOI) {
                bp += (offset - 1);
                token = JSONToken.EOF;
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return array;
    }

    public final float[][] scanFieldFloatArray2(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return null;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        if (chLocal != '[') {
            matchStat = NOT_MATCH_NAME;
            return null;
        }
        chLocal = charAt(bp + (offset++));

        float[][] arrayarray = new float[16][];
        int arrayarrayIndex = 0;

        for (;;) {
            if (chLocal == '[') {
                chLocal = charAt(bp + (offset++));

                float[] array = new float[16];
                int arrayIndex = 0;

                for (; ; ) {
                    int start = bp + offset - 1;
                    boolean negative = chLocal == '-';
                    if (negative) {
                        chLocal = charAt(bp + (offset++));
                    }

                    if (chLocal >= '0' && chLocal <= '9') {
                        int intVal = chLocal - '0';
                        for (; ; ) {
                            chLocal = charAt(bp + (offset++));

                            if (chLocal >= '0' && chLocal <= '9') {
                                intVal = intVal * 10 + (chLocal - '0');
                                continue;
                            } else {
                                break;
                            }
                        }

                        int power = 1;
                        if (chLocal == '.') {
                            chLocal = charAt(bp + (offset++));

                            if (chLocal >= '0' && chLocal <= '9') {
                                intVal = intVal * 10 + (chLocal - '0');
                                power = 10;
                                for (; ; ) {
                                    chLocal = charAt(bp + (offset++));

                                    if (chLocal >= '0' && chLocal <= '9') {
                                        intVal = intVal * 10 + (chLocal - '0');
                                        power *= 10;
                                        continue;
                                    } else {
                                        break;
                                    }
                                }
                            } else {
                                matchStat = NOT_MATCH;
                                return null;
                            }
                        }

                        boolean exp = chLocal == 'e' || chLocal == 'E';
                        if (exp) {
                            chLocal = charAt(bp + (offset++));
                            if (chLocal == '+' || chLocal == '-') {
                                chLocal = charAt(bp + (offset++));
                            }
                            for (;;) {
                                if (chLocal >= '0' && chLocal <= '9') {
                                    chLocal = charAt(bp + (offset++));
                                } else {
                                    break;
                                }
                            }
                        }

                        int count = bp + offset - start - 1;
                        float value;
                        if (!exp && count < 10) {
                            value = ((float) intVal) / power;
                            if (negative) {
                                value = -value;
                            }
                        } else {
                            String text = this.subString(start, count);
                            value = Float.parseFloat(text);
                        }

                        if (arrayIndex >= array.length) {
                            float[] tmp = new float[array.length * 3 / 2];
                            System.arraycopy(array, 0, tmp, 0, arrayIndex);
                            array = tmp;
                        }
                        array[arrayIndex++] = value;

                        if (chLocal == ',') {
                            chLocal = charAt(bp + (offset++));
                        } else if (chLocal == ']') {
                            chLocal = charAt(bp + (offset++));
                            break;
                        }
                    } else {
                        matchStat = NOT_MATCH;
                        return null;
                    }
                }

                // compact
                if (arrayIndex != array.length) {
                    float[] tmp = new float[arrayIndex];
                    System.arraycopy(array, 0, tmp, 0, arrayIndex);
                    array = tmp;
                }

                if (arrayarrayIndex >= arrayarray.length) {
                    float[][] tmp = new float[arrayarray.length * 3 / 2][];
                    System.arraycopy(array, 0, tmp, 0, arrayIndex);
                    arrayarray = tmp;
                }
                arrayarray[arrayarrayIndex++] = array;

                if (chLocal == ',') {
                    chLocal = charAt(bp + (offset++));
                } else if (chLocal == ']') {
                    chLocal = charAt(bp + (offset++));
                    break;
                }
            }
        }

        // compact
        if (arrayarrayIndex != arrayarray.length) {
            float[][] tmp = new float[arrayarrayIndex][];
            System.arraycopy(arrayarray, 0, tmp, 0, arrayarrayIndex);
            arrayarray = tmp;
        }

        if (chLocal == ',') {
            bp += (offset - 1);
            this.next();
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return arrayarray;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == EOI) {
                bp += (offset - 1);
                token = JSONToken.EOF;
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return arrayarray;
    }

    public final double scanFieldDouble(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return 0;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));
        final boolean quote = chLocal == '"';
        if (quote) {
            chLocal = charAt(bp + (offset++));
        }

        boolean negative = chLocal == '-';
        if (negative) {
            chLocal = charAt(bp + (offset++));
        }

        double value;
        if (chLocal >= '0' && chLocal <= '9') {
            long intVal = chLocal - '0';

            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    intVal = intVal * 10 + (chLocal - '0');
                    continue;
                } else {
                    break;
                }
            }

            long power = 1;
            boolean small = (chLocal == '.');
            if (small) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    intVal = intVal * 10 + (chLocal - '0');
                    power = 10;
                    for (;;) {
                        chLocal = charAt(bp + (offset++));
                        if (chLocal >= '0' && chLocal <= '9') {
                            intVal = intVal * 10 + (chLocal - '0');
                            power *= 10;
                            continue;
                        } else {
                            break;
                        }
                    }
                } else {
                    matchStat = NOT_MATCH;
                    return 0;
                }
            }

            boolean exp = chLocal == 'e' || chLocal == 'E';
            if (exp) {
                chLocal = charAt(bp + (offset++));
                if (chLocal == '+' || chLocal == '-') {
                    chLocal = charAt(bp + (offset++));
                }
                for (;;) {
                    if (chLocal >= '0' && chLocal <= '9') {
                        chLocal = charAt(bp + (offset++));
                    } else {
                        break;
                    }
                }
            }

            int start, count;
            if (quote) {
                if (chLocal != '"') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else {
                    chLocal = charAt(bp + (offset++));
                }
                start = bp + fieldName.length + 1;
                count = bp + offset - start - 2;
            } else {
                start = bp + fieldName.length;
                count = bp + offset - start - 1;
            }

            if (!exp && count < 20) {
                value = ((double) intVal) / power;
                if (negative) {
                    value = -value;
                }
            } else {
                String text = this.subString(start, count);
                value = Double.parseDouble(text);
            }
        } else if (chLocal == 'n' && charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
            matchStat = VALUE_NULL;
            value = 0;
            offset += 3;
            chLocal = charAt(bp + offset++);

            if (quote && chLocal == '"') {
                chLocal = charAt(bp + offset++);
            }

            for (;;) {
                if (chLocal == ',') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.COMMA;
                    return value;
                } else if (chLocal == '}') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.RBRACE;
                    return value;
                } else if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + offset++);
                    continue;
                }
                break;
            }
            matchStat = NOT_MATCH;
            return 0;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return 0;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        return value;
    }

    public BigDecimal scanFieldDecimal(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return null;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));
        final boolean quote = chLocal == '"';
        if (quote) {
            chLocal = charAt(bp + (offset++));
        }

        boolean negative = chLocal == '-';
        if (negative) {
            chLocal = charAt(bp + (offset++));
        }

        BigDecimal value;
        if (chLocal >= '0' && chLocal <= '9') {
            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    continue;
                } else {
                    break;
                }
            }

            boolean small = (chLocal == '.');
            if (small) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    for (;;) {
                        chLocal = charAt(bp + (offset++));
                        if (chLocal >= '0' && chLocal <= '9') {
                            continue;
                        } else {
                            break;
                        }
                    }
                } else {
                    matchStat = NOT_MATCH;
                    return null;
                }
            }

            boolean exp = chLocal == 'e' || chLocal == 'E';
            if (exp) {
                chLocal = charAt(bp + (offset++));
                if (chLocal == '+' || chLocal == '-') {
                    chLocal = charAt(bp + (offset++));
                }
                for (;;) {
                    if (chLocal >= '0' && chLocal <= '9') {
                        chLocal = charAt(bp + (offset++));
                    } else {
                        break;
                    }
                }
            }

            int start, count;
            if (quote) {
                if (chLocal != '"') {
                    matchStat = NOT_MATCH;
                    return null;
                } else {
                    chLocal = charAt(bp + (offset++));
                }
                start = bp + fieldName.length + 1;
                count = bp + offset - start - 2;
            } else {
                start = bp + fieldName.length;
                count = bp + offset - start - 1;
            }

            char[] chars = this.sub_chars(start, count);
            value = new BigDecimal(chars);
        } else if (chLocal == 'n' && charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
            matchStat = VALUE_NULL;
            value = null;
            offset += 3;
            chLocal = charAt(bp + offset++);

            if (quote && chLocal == '"') {
                chLocal = charAt(bp + offset++);
            }

            for (;;) {
                if (chLocal == ',') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.COMMA;
                    return value;
                } else if (chLocal == '}') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.RBRACE;
                    return value;
                } else if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + offset++);
                    continue;
                }
                break;
            }
            matchStat = NOT_MATCH;
            return null;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return value;
    }

    public BigInteger scanFieldBigInteger(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return null;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));
        final boolean quote = chLocal == '"';
        if (quote) {
            chLocal = charAt(bp + (offset++));
        }

        boolean negative = chLocal == '-';
        if (negative) {
            chLocal = charAt(bp + (offset++));
        }

        BigInteger value;
        if (chLocal >= '0' && chLocal <= '9') {
            long intVal = chLocal - '0';
            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    intVal = intVal * 10 + (chLocal - '0');
                    continue;
                } else {
                    break;
                }
            }

            int start, count;
            if (quote) {
                if (chLocal != '"') {
                    matchStat = NOT_MATCH;
                    return null;
                } else {
                    chLocal = charAt(bp + (offset++));
                }
                start = bp + fieldName.length + 1;
                count = bp + offset - start - 2;
            } else {
                start = bp + fieldName.length;
                count = bp + offset - start - 1;
            }

            if (count < 20 || (negative && count < 21)) {
                value = BigInteger.valueOf(negative ? -intVal : intVal);
            } else {

//            char[] chars = this.sub_chars(negative ? start + 1 : start, count);
//            value = new BigInteger(chars, )
                String strVal = this.subString(start, count);
                value = new BigInteger(strVal);
            }
        } else if (chLocal == 'n' && charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
            matchStat = VALUE_NULL;
            value = null;
            offset += 3;
            chLocal = charAt(bp + offset++);

            if (quote && chLocal == '"') {
                chLocal = charAt(bp + offset++);
            }

            for (;;) {
                if (chLocal == ',') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.COMMA;
                    return value;
                } else if (chLocal == '}') {
                    bp += offset;
                    this.ch = charAt(bp);
                    matchStat = VALUE_NULL;
                    token = JSONToken.RBRACE;
                    return value;
                } else if (isWhitespace(chLocal)) {
                    chLocal = charAt(bp + offset++);
                    continue;
                }
                break;
            }
            matchStat = NOT_MATCH;
            return null;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return value;
    }

    public java.util.Date scanFieldDate(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return null;
        }

        // int index = bp + fieldName.length;

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        final java.util.Date dateVal;
        if (chLocal == '"'){
            int startIndex = bp + fieldName.length + 1;
            int endIndex = indexOf('"', startIndex);
            if (endIndex == -1) {
                throw new JSONException("unclosed str");
            }

            int startIndex2 = bp + fieldName.length + 1; // must re compute
            String stringVal = subString(startIndex2, endIndex - startIndex2);
            if (stringVal.indexOf('\\') != -1) {
                for (;;) {
                    int slashCount = 0;
                    for (int i = endIndex - 1; i >= 0; --i) {
                        if (charAt(i) == '\\') {
                            slashCount++;
                        } else {
                            break;
                        }
                    }
                    if (slashCount % 2 == 0) {
                        break;
                    }
                    endIndex = indexOf('"', endIndex + 1);
                }

                int chars_len = endIndex - (bp + fieldName.length + 1);
                char[] chars = sub_chars( bp + fieldName.length + 1, chars_len);

                stringVal = readString(chars, chars_len);
            }

            offset += (endIndex - (bp + fieldName.length + 1) + 1);
            chLocal = charAt(bp + (offset++));

            JSONScanner dateLexer = new JSONScanner(stringVal);
            try {
                if (dateLexer.scanISO8601DateIfMatch(false)) {
                    Calendar calendar = dateLexer.getCalendar();
                    dateVal = calendar.getTime();
                } else {
                    matchStat = NOT_MATCH;
                    return null;
                }
            } finally {
                dateLexer.close();
            }
        } else if (chLocal == '-' || (chLocal >= '0' && chLocal <= '9')) {
            long millis = 0;

            boolean negative = false;
            if (chLocal == '-') {
                chLocal = charAt(bp + (offset++));
                negative = true;
            }

            if (chLocal >= '0' && chLocal <= '9') {
                millis = chLocal - '0';
                for (; ; ) {
                    chLocal = charAt(bp + (offset++));
                    if (chLocal >= '0' && chLocal <= '9') {
                        millis = millis * 10 + (chLocal - '0');
                    } else {
                        break;
                    }
                }
            }

            if (millis < 0) {
                matchStat = NOT_MATCH;
                return null;
            }

            if (negative) {
                millis = -millis;
            }

            dateVal = new java.util.Date(millis);
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            return dateVal;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return dateVal;
    }

    public java.util.Date scanDate(char seperator) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));

        final java.util.Date dateVal;
        if (chLocal == '"'){
            int startIndex = bp + 1;
            int endIndex = indexOf('"', startIndex);
            if (endIndex == -1) {
                throw new JSONException("unclosed str");
            }

            int startIndex2 = bp + 1; // must re compute
            String stringVal = subString(startIndex2, endIndex - startIndex2);
            if (stringVal.indexOf('\\') != -1) {
                for (;;) {
                    int slashCount = 0;
                    for (int i = endIndex - 1; i >= 0; --i) {
                        if (charAt(i) == '\\') {
                            slashCount++;
                        } else {
                            break;
                        }
                    }
                    if (slashCount % 2 == 0) {
                        break;
                    }
                    endIndex = indexOf('"', endIndex + 1);
                }

                int chars_len = endIndex - (bp + 1);
                char[] chars = sub_chars( bp + 1, chars_len);

                stringVal = readString(chars, chars_len);
            }

            offset += (endIndex - (bp + 1) + 1);
            chLocal = charAt(bp + (offset++));

            JSONScanner dateLexer = new JSONScanner(stringVal);
            try {
                if (dateLexer.scanISO8601DateIfMatch(false)) {
                    Calendar calendar = dateLexer.getCalendar();
                    dateVal = calendar.getTime();
                } else {
                    matchStat = NOT_MATCH;
                    return null;
                }
            } finally {
                dateLexer.close();
            }
        } else if (chLocal == '-' || (chLocal >= '0' && chLocal <= '9')) {
            long millis = 0;

            boolean negative = false;
            if (chLocal == '-') {
                chLocal = charAt(bp + (offset++));
                negative = true;
            }

            if (chLocal >= '0' && chLocal <= '9') {
                millis = chLocal - '0';
                for (; ; ) {
                    chLocal = charAt(bp + (offset++));
                    if (chLocal >= '0' && chLocal <= '9') {
                        millis = millis * 10 + (chLocal - '0');
                    } else {
                        break;
                    }
                }
            }

            if (millis < 0) {
                matchStat = NOT_MATCH;
                return null;
            }

            if (negative) {
                millis = -millis;
            }

            dateVal = new java.util.Date(millis);
        } else if (chLocal == 'n' && charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
            matchStat = VALUE_NULL;
            dateVal = null;
            offset += 3;
            chLocal = charAt(bp + offset++);
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return dateVal;
        }

        if (chLocal == ']') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return dateVal;
    }

    public java.util.UUID scanFieldUUID(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return null;
        }

        // int index = bp + fieldName.length;

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        final java.util.UUID uuid;
        if (chLocal == '"') {
            int startIndex = bp + fieldName.length + 1;
            int endIndex = indexOf('"', startIndex);
            if (endIndex == -1) {
                throw new JSONException("unclosed str");
            }

            int startIndex2 = bp + fieldName.length + 1; // must re compute
            int len = endIndex - startIndex2;
            if (len == 36) {
                long mostSigBits = 0, leastSigBits = 0;
                for (int i = 0; i < 8; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    mostSigBits <<= 4;
                    mostSigBits |= num;
                }
                for (int i = 9; i < 13; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    mostSigBits <<= 4;
                    mostSigBits |= num;
                }
                for (int i = 14; i < 18; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    mostSigBits <<= 4;
                    mostSigBits |= num;
                }
                for (int i = 19; i < 23; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    leastSigBits <<= 4;
                    leastSigBits |= num;
                }
                for (int i = 24; i < 36; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    leastSigBits <<= 4;
                    leastSigBits |= num;
                }
                uuid = new UUID(mostSigBits, leastSigBits);

                offset += (endIndex - (bp + fieldName.length + 1) + 1);
                chLocal = charAt(bp + (offset++));
            } else if (len == 32) {
                long mostSigBits = 0, leastSigBits = 0;
                for (int i = 0; i < 16; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    mostSigBits <<= 4;
                    mostSigBits |= num;
                }
                for (int i = 16; i < 32; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    leastSigBits <<= 4;
                    leastSigBits |= num;
                }

                uuid = new UUID(mostSigBits, leastSigBits);

                offset += (endIndex - (bp + fieldName.length + 1) + 1);
                chLocal = charAt(bp + (offset++));
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
        } else if (chLocal == 'n'
                && charAt(bp + (offset++)) == 'u'
                && charAt(bp + (offset++)) == 'l'
                && charAt(bp + (offset++)) == 'l') {
            uuid = null;
            chLocal = charAt(bp + (offset++));
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            return uuid;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return uuid;
    }

    public java.util.UUID scanUUID(char seperator) {
        matchStat = UNKNOWN;

        // int index = bp + fieldName.length;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));

        final java.util.UUID uuid;
        if (chLocal == '"') {
            int startIndex = bp + 1;
            int endIndex = indexOf('"', startIndex);
            if (endIndex == -1) {
                throw new JSONException("unclosed str");
            }

            int startIndex2 = bp + 1; // must re compute
            int len = endIndex - startIndex2;
            if (len == 36) {
                long mostSigBits = 0, leastSigBits = 0;
                for (int i = 0; i < 8; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    mostSigBits <<= 4;
                    mostSigBits |= num;
                }
                for (int i = 9; i < 13; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    mostSigBits <<= 4;
                    mostSigBits |= num;
                }
                for (int i = 14; i < 18; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    mostSigBits <<= 4;
                    mostSigBits |= num;
                }
                for (int i = 19; i < 23; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    leastSigBits <<= 4;
                    leastSigBits |= num;
                }
                for (int i = 24; i < 36; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    leastSigBits <<= 4;
                    leastSigBits |= num;
                }
                uuid = new UUID(mostSigBits, leastSigBits);

                offset += (endIndex - (bp + 1) + 1);
                chLocal = charAt(bp + (offset++));
            } else if (len == 32) {
                long mostSigBits = 0, leastSigBits = 0;
                for (int i = 0; i < 16; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    mostSigBits <<= 4;
                    mostSigBits |= num;
                }
                for (int i = 16; i < 32; ++i) {
                    char ch = charAt(startIndex2 + i);
                    int num;
                    if (ch >= '0' && ch <= '9') {
                        num = ch - '0';
                    } else if (ch >= 'a' && ch <= 'f') {
                        num = 10 + (ch - 'a');
                    } else if (ch >= 'A' && ch <= 'F') {
                        num = 10 + (ch - 'A');
                    } else {
                        matchStat = NOT_MATCH_NAME;
                        return null;
                    }

                    leastSigBits <<= 4;
                    leastSigBits |= num;
                }

                uuid = new UUID(mostSigBits, leastSigBits);

                offset += (endIndex - (bp + 1) + 1);
                chLocal = charAt(bp + (offset++));
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
        } else if (chLocal == 'n'
                && charAt(bp + (offset++)) == 'u'
                && charAt(bp + (offset++)) == 'l'
                && charAt(bp + (offset++)) == 'l') {
            uuid = null;
            chLocal = charAt(bp + (offset++));
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        if (chLocal == ',') {
            bp += offset;
            this.ch = this.charAt(bp);
            matchStat = VALUE;
            return uuid;
        }

        if (chLocal == ']') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += offset;
                this.ch = this.charAt(bp);
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return uuid;
    }

    public final void scanTrue() {
        if (ch != 't') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch != 'r') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch != 'u') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch != 'e') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch == ' ' || ch == ',' || ch == '}' || ch == ']' || ch == '\n' || ch == '\r' || ch == '\t' || ch == EOI
                || ch == '\f' || ch == '\b' || ch == ':' || ch == '/') {
            /** 兼容性防御，标记是true的token */
            token = JSONToken.TRUE;
        } else {
            throw new JSONException("scan true error");
        }
    }

    public final void scanNullOrNew() {
        if (ch != 'n') {
            throw new JSONException("error parse null or new");
        }
        next();

        if (ch == 'u') {
            next();
            if (ch != 'l') {
                throw new JSONException("error parse null");
            }
            next();

            if (ch != 'l') {
                throw new JSONException("error parse null");
            }
            next();

            if (ch == ' ' || ch == ',' || ch == '}' || ch == ']' || ch == '\n' || ch == '\r' || ch == '\t' || ch == EOI
                    || ch == '\f' || ch == '\b') {
                token = JSONToken.NULL;
            } else {
                throw new JSONException("scan null error");
            }
            return;
        }

        if (ch != 'e') {
            throw new JSONException("error parse new");
        }
        next();

        if (ch != 'w') {
            throw new JSONException("error parse new");
        }
        next();

        if (ch == ' ' || ch == ',' || ch == '}' || ch == ']' || ch == '\n' || ch == '\r' || ch == '\t' || ch == EOI
                || ch == '\f' || ch == '\b') {
            token = JSONToken.NEW;
        } else {
            throw new JSONException("scan new error");
        }
    }

    public final void scanFalse() {
        if (ch != 'f') {
            throw new JSONException("error parse false");
        }
        next();

        if (ch != 'a') {
            throw new JSONException("error parse false");
        }
        next();

        if (ch != 'l') {
            throw new JSONException("error parse false");
        }
        next();

        if (ch != 's') {
            throw new JSONException("error parse false");
        }
        next();

        if (ch != 'e') {
            throw new JSONException("error parse false");
        }
        next();

        if (ch == ' ' || ch == ',' || ch == '}' || ch == ']' || ch == '\n' || ch == '\r' || ch == '\t' || ch == EOI
                || ch == '\f' || ch == '\b' || ch == ':' || ch == '/') {
            token = JSONToken.FALSE;
        } else {
            throw new JSONException("scan false error");
        }
    }

    public final void scanIdent() {
        /** 记录当前流中token的开始位置, np指向当前token前一个字符 */
        np = bp - 1;
        hasSpecial = false;

        for (;;) {
            sp++;

            next();
            /** 如果是字母或数字，继续读取 */
            if (Character.isLetterOrDigit(ch)) {
                continue;
            }

            /** 获取字符串值 */
            String ident = stringVal();

            if ("null".equalsIgnoreCase(ident)) {
                token = JSONToken.NULL;
            } else if ("new".equals(ident)) {
                token = JSONToken.NEW;
            } else if ("true".equals(ident)) {
                token = JSONToken.TRUE;
            } else if ("false".equals(ident)) {
                token = JSONToken.FALSE;
            } else if ("undefined".equals(ident)) {
                token = JSONToken.UNDEFINED;
            } else if ("Set".equals(ident)) {
                token = JSONToken.SET;
            } else if ("TreeSet".equals(ident)) {
                token = JSONToken.TREE_SET;
            } else {
                token = JSONToken.IDENTIFIER;
            }
            return;
        }
    }

    public abstract String stringVal();

    /** 子字符串在JDK6和JDK7不一样,
     *  JDK6 默认实现获取子串会共享原字符串char[]，导致字符串无法释放
     */
    public abstract String subString(int offset, int count);

    protected abstract char[] sub_chars(int offset, int count);

    public static String readString(char[] chars, int chars_len) {
        char[] sbuf = new char[chars_len];
        int len = 0;
        for (int i = 0; i < chars_len; ++i) {
            char ch = chars[i];

            if (ch != '\\') {
                sbuf[len++] = ch;
                continue;
            }
            ch = chars[++i];

            switch (ch) {
                case '0':
                    sbuf[len++] = '\0';
                    break;
                case '1':
                    sbuf[len++] = '\1';
                    break;
                case '2':
                    sbuf[len++] = '\2';
                    break;
                case '3':
                    sbuf[len++] = '\3';
                    break;
                case '4':
                    sbuf[len++] = '\4';
                    break;
                case '5':
                    sbuf[len++] = '\5';
                    break;
                case '6':
                    sbuf[len++] = '\6';
                    break;
                case '7':
                    sbuf[len++] = '\7';
                    break;
                case 'b': // 8
                    sbuf[len++] = '\b';
                    break;
                case 't': // 9
                    sbuf[len++] = '\t';
                    break;
                case 'n': // 10
                    sbuf[len++] = '\n';
                    break;
                case 'v': // 11
                    sbuf[len++] = '\u000B';
                    break;
                case 'f': // 12
                case 'F':
                    sbuf[len++] = '\f';
                    break;
                case 'r': // 13
                    sbuf[len++] = '\r';
                    break;
                case '"': // 34
                    sbuf[len++] = '"';
                    break;
                case '\'': // 39
                    sbuf[len++] = '\'';
                    break;
                case '/': // 47
                    sbuf[len++] = '/';
                    break;
                case '\\': // 92
                    sbuf[len++] = '\\';
                    break;
                case 'x':
                    sbuf[len++] = (char) (digits[chars[++i]] * 16 + digits[chars[++i]]);
                    break;
                case 'u':
                    sbuf[len++] = (char) Integer.parseInt(new String(new char[] { chars[++i], //
                                    chars[++i], //
                                    chars[++i], //
                                    chars[++i] }),
                            16);
                    break;
                default:
                    throw new JSONException("unclosed.str.lit");
            }
        }
        return new String(sbuf, 0, len);
    }

    protected abstract boolean charArrayCompare(char[] chars);

    public boolean isBlankInput() {
        for (int i = 0;; ++i) {
            char chLocal = charAt(i);
            if (chLocal == EOI) {
                token = JSONToken.EOF;
                break;
            }

            if (!isWhitespace(chLocal)) {
                return false;
            }
        }

        return true;
    }

    public final void skipWhitespace() {
        for (;;) {
            if (ch <= '/') {
                if (ch == ' ' || ch == '\r' || ch == '\n' || ch == '\t' || ch == '\f' || ch == '\b') {
                    next();
                    continue;
                } else if (ch == '/') {
                    skipComment();
                    continue;
                } else {
                    break;
                }
            } else {
                break;
            }
        }
    }

    private void scanStringSingleQuote() {
        np = bp;
        hasSpecial = false;
        char chLocal;
        for (;;) {
            chLocal = next();

            if (chLocal == '\'') {
                break;
            }

            if (chLocal == EOI) {
                if (!isEOF()) {
                    putChar((char) EOI);
                    continue;
                }
                throw new JSONException("unclosed single-quote string");
            }

            if (chLocal == '\\') {
                if (!hasSpecial) {
                    hasSpecial = true;

                    if (sp > sbuf.length) {
                        char[] newsbuf = new char[sp * 2];
                        System.arraycopy(sbuf, 0, newsbuf, 0, sbuf.length);
                        sbuf = newsbuf;
                    }

                    // text.getChars(offset, offset + count, dest, 0);
                    this.copyTo(np + 1, sp, sbuf);
                    // System.arraycopy(buf, np + 1, sbuf, 0, sp);
                }

                chLocal = next();

                switch (chLocal) {
                    case '0':
                        putChar('\0');
                        break;
                    case '1':
                        putChar('\1');
                        break;
                    case '2':
                        putChar('\2');
                        break;
                    case '3':
                        putChar('\3');
                        break;
                    case '4':
                        putChar('\4');
                        break;
                    case '5':
                        putChar('\5');
                        break;
                    case '6':
                        putChar('\6');
                        break;
                    case '7':
                        putChar('\7');
                        break;
                    case 'b': // 8
                        putChar('\b');
                        break;
                    case 't': // 9
                        putChar('\t');
                        break;
                    case 'n': // 10
                        putChar('\n');
                        break;
                    case 'v': // 11
                        putChar('\u000B');
                        break;
                    case 'f': // 12
                    case 'F':
                        putChar('\f');
                        break;
                    case 'r': // 13
                        putChar('\r');
                        break;
                    case '"': // 34
                        putChar('"');
                        break;
                    case '\'': // 39
                        putChar('\'');
                        break;
                    case '/': // 47
                        putChar('/');
                        break;
                    case '\\': // 92
                        putChar('\\');
                        break;
                    case 'x':
                        putChar((char) (digits[next()] * 16 + digits[next()]));
                        break;
                    case 'u':
                        putChar((char) Integer.parseInt(new String(new char[] { next(), next(), next(), next() }), 16));
                        break;
                    default:
                        this.ch = chLocal;
                        throw new JSONException("unclosed single-quote string");
                }
                continue;
            }

            if (!hasSpecial) {
                sp++;
                continue;
            }

            if (sp == sbuf.length) {
                putChar(chLocal);
            } else {
                sbuf[sp++] = chLocal;
            }
        }

        token = LITERAL_STRING;
        this.next();
    }

    /**
     * Append a character to sbuf.
     */
    protected final void putChar(char ch) {
        /** 如果buffer空间不够， 执行2倍扩容*/
        if (sp == sbuf.length) {
            char[] newsbuf = new char[sbuf.length * 2];
            System.arraycopy(sbuf, 0, newsbuf, 0, sbuf.length);
            sbuf = newsbuf;
        }
        sbuf[sp++] = ch;
    }

    public final void scanHex() {
        if (ch != 'x') {
            throw new JSONException("illegal state. " + ch);
        }
        next();
        /** 十六进制x紧跟着单引号 */
        /** @see com.alibaba.fastjson.serializer.SerializeWriter#writeHex(byte[]) */
        if (ch != '\'') {
            throw new JSONException("illegal state. " + ch);
        }

        np = bp;
        /** 这里一次next, for循环也读一次next, 因为十六进制被写成2个字节的单字符 */
        next();

        for (int i = 0;;++i) {
            char ch = next();
            if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F')) {
                sp++;
                continue;
            } else if (ch == '\'') {
                sp++;
                /** 遇到结束符号，自动预读下一个字符 */
                next();
                break;
            } else {
                throw new JSONException("illegal state. " + ch);
            }
        }
        token = JSONToken.HEX;
    }

    public final void scanNumber() {
        /** 记录当前流中token的开始位置, np指向数字字符索引 */
        np = bp;

        /** 兼容处理负数 */
        if (ch == '-') {
            sp++;
            next();
        }

        for (;;) {
            if (ch >= '0' && ch <= '9') {
                /** 如果是数字字符，递增索引位置 */
                sp++;
            } else {
                break;
            }
            next();
        }

        boolean isDouble = false;

        /** 如果遇到小数点字符 */
        if (ch == '.') {
            sp++;
            /** 继续读小数点后面字符 */
            next();
            isDouble = true;

            for (;;) {
                if (ch >= '0' && ch <= '9') {
                    sp++;
                } else {
                    break;
                }
                next();
            }
        }

        /** 继续读取数字后面的类型 */
        if (ch == 'L') {
            sp++;
            next();
        } else if (ch == 'S') {
            sp++;
            next();
        } else if (ch == 'B') {
            sp++;
            next();
        } else if (ch == 'F') {
            sp++;
            next();
            isDouble = true;
        } else if (ch == 'D') {
            sp++;
            next();
            isDouble = true;
        } else if (ch == 'e' || ch == 'E') {

            /** 扫描科学计数法 */
            sp++;
            next();

            if (ch == '+' || ch == '-') {
                sp++;
                next();
            }

            for (;;) {
                if (ch >= '0' && ch <= '9') {
                    sp++;
                } else {
                    break;
                }
                next();
            }

            if (ch == 'D' || ch == 'F') {
                sp++;
                next();
            }

            isDouble = true;
        }

        if (isDouble) {
            token = JSONToken.LITERAL_FLOAT;
        } else {
            token = JSONToken.LITERAL_INT;
        }
    }

    public final long longValue() throws NumberFormatException {
        long result = 0;
        boolean negative = false;
        long limit;
        int digit;

        if (np == -1) {
            np = 0;
        }

        int i = np, max = np + sp;

        if (charAt(np) == '-') {
            negative = true;
            limit = Long.MIN_VALUE;
            i++;
        } else {
            limit = -Long.MAX_VALUE;
        }
        long multmin = MULTMIN_RADIX_TEN;
        if (i < max) {
            digit = charAt(i++) - '0';
            result = -digit;
        }
        while (i < max) {
            // Accumulating negatively avoids surprises near MAX_VALUE
            char chLocal = charAt(i++);

            if (chLocal == 'L' || chLocal == 'S' || chLocal == 'B') {
                break;
            }

            digit = chLocal - '0';
            if (result < multmin) {
                throw new NumberFormatException(numberString());
            }
            result *= 10;
            if (result < limit + digit) {
                throw new NumberFormatException(numberString());
            }
            result -= digit;
        }

        if (negative) {
            if (i > np + 1) {
                return result;
            } else { /* Only got "-" */
                throw new NumberFormatException(numberString());
            }
        } else {
            return -result;
        }
    }

    public final Number decimalValue(boolean decimal) {
        char chLocal = charAt(np + sp - 1);
        try {
            if (chLocal == 'F') {
                return Float.parseFloat(numberString());
            }

            if (chLocal == 'D') {
                return Double.parseDouble(numberString());
            }

            if (decimal) {
                return decimalValue();
            } else {
                return doubleValue();
            }
        } catch (NumberFormatException ex) {
            throw new JSONException(ex.getMessage() + ", " + info());
        }
    }

    public abstract BigDecimal decimalValue();

    public static boolean isWhitespace(char ch) {
        // 专门调整了判断顺序
        return ch <= ' ' && (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b');
    }

    protected static final long  MULTMIN_RADIX_TEN     = Long.MIN_VALUE / 10;
    protected static final int   INT_MULTMIN_RADIX_TEN = Integer.MIN_VALUE / 10;

    protected final static int[] digits                = new int[(int) 'f' + 1];

    static {
        for (int i = '0'; i <= '9'; ++i) {
            /** 索引48存储数字0，依次类推 */
            digits[i] = i - '0';
        }

        for (int i = 'a'; i <= 'f'; ++i) {
            /** 索引97存储10，依次类推 */
            digits[i] = (i - 'a') + 10;
        }
        for (int i = 'A'; i <= 'F'; ++i) {
            /** 索引65存储10，依次类推 */
            digits[i] = (i - 'A') + 10;
        }
    }

    /**
     * hsf support
     * @param fieldName
     * @param argTypesCount
     * @param typeSymbolTable
     * @return
     */
    public String[] scanFieldStringArray(char[] fieldName, int argTypesCount, SymbolTable typeSymbolTable) {
        throw new UnsupportedOperationException();
    }

    public boolean matchField2(char[] fieldName) {
        throw new UnsupportedOperationException();
    }

    public int getFeatures() {
        return this.features;
    }
}
