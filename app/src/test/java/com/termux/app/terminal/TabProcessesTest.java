package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TabProcessesTest {

    private static List<String> parse(String... argv) {
        return TabProcesses.parseSshArgs(Arrays.asList(argv));
    }

    @Test
    public void tabBarSshTab() {
        Assert.assertEquals(Arrays.asList("tarrabah"),
            parse("/data/data/com.termux/files/usr/bin/ssh", "-t", "--", "tarrabah", "tmux new -A -s main"));
    }

    @Test
    public void keepsHostOptions() {
        Assert.assertEquals(Arrays.asList("-p", "2222", "-i", "k", "user@h"),
            parse("ssh", "-p", "2222", "-i", "k", "user@h", "ls"));
        Assert.assertEquals(Arrays.asList("-o", "ProxyJump=j", "h"), parse("ssh", "-o", "ProxyJump=j", "h"));
        Assert.assertEquals(Arrays.asList("-p", "22", "h"), parse("ssh", "-tp22", "h"));
    }

    @Test
    public void dropsForwards() {
        Assert.assertEquals(Arrays.asList("h"), parse("ssh", "-L", "8080:x:80", "-N", "h"));
    }

    @Test
    public void unclear() {
        Assert.assertNull(parse("ssh"));
        Assert.assertNull(parse("ssh", "-p"));
        Assert.assertNull(parse("ssh", "--", "-evil"));
        Assert.assertNull(parse("ssh", "h;rm"));
    }

}
