package com.alsekretary.app.domain

import org.junit.Assert.*
import org.junit.Test

class MarkdownParserTest {
    @Test fun parsesMobileMarkdownBlocks() {
        val source = """# عنوان
- [x] مهمة
```python
print(1)
```
$$
x^2
$$"""
        val blocks = MarkdownParser.parse(source)
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertEquals(true, (blocks[1] as MarkdownBlock.Bullet).checked)
        assertEquals("python", (blocks[2] as MarkdownBlock.Code).language)
        assertTrue(blocks[3] is MarkdownBlock.Math)
    }
}
