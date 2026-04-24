package io.xmake.lang.editor.commenting

import io.xmake.lang.XMakeTestCase

class XMakeLuaCommenterTest : XMakeTestCase() {

    fun testExposesLuaCommentPrefixes() {
        val commenter = XMakeLuaCommenter()

        assertEquals("--", commenter.lineCommentPrefix)
        assertEquals("--[[", commenter.blockCommentPrefix)
        assertEquals("]]", commenter.blockCommentSuffix)
        assertNull(commenter.commentedBlockCommentPrefix)
        assertNull(commenter.commentedBlockCommentSuffix)
    }
}
