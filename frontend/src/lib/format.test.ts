import { describe, expect, it } from 'vitest'
import { clamp, formatDistance, formatScore } from './format'

describe('formatDistance', () => {
  it('shows metres under a kilometre', () => {
    expect(formatDistance(0)).toBe('0 m')
    expect(formatDistance(742.4)).toBe('742 m')
  })

  it('shows one decimal for a few kilometres', () => {
    expect(formatDistance(1500)).toBe('1.5 km')
  })

  it('rounds to whole kilometres past ten', () => {
    expect(formatDistance(4_394_545)).toBe('4,395 km')
  })
})

describe('formatScore', () => {
  it('thousands-separates', () => {
    expect(formatScore(263)).toBe('263')
    expect(formatScore(24999)).toBe('24,999')
  })
})

describe('clamp', () => {
  it('bounds a value', () => {
    expect(clamp(5, 0, 10)).toBe(5)
    expect(clamp(-1, 0, 10)).toBe(0)
    expect(clamp(11, 0, 10)).toBe(10)
  })
})
