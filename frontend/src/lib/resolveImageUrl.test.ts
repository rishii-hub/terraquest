import { describe, expect, it } from 'vitest'
import { resolveImageUrl } from './resolveImageUrl'

describe('resolveImageUrl', () => {
  it('rewrites a local file:// URL to the dev imagery path', () => {
    const raw = 'file:///C:/Users/x/AppData/Local/Temp/terraquest-imagery/img/032e759b-a582-4ec7-80d5-9417b359a7b0'
    expect(resolveImageUrl(raw)).toBe('/__img/img/032e759b-a582-4ec7-80d5-9417b359a7b0')
  })

  it('rewrites a unix-style file:// URL', () => {
    const raw = 'file:///tmp/terraquest-imagery/img/abc12345-0000-0000-0000-000000000000'
    expect(resolveImageUrl(raw)).toBe('/__img/img/abc12345-0000-0000-0000-000000000000')
  })

  it('passes through an https presigned URL unchanged', () => {
    const raw = 'https://cdn.example.com/img/abc?signature=xyz'
    expect(resolveImageUrl(raw)).toBe(raw)
  })

  it('leaves a file URL it cannot parse untouched', () => {
    const raw = 'file:///weird/path/without-a-key'
    expect(resolveImageUrl(raw)).toBe(raw)
  })
})
