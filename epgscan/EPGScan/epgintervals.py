# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later

from bisect import bisect_right


def merge_intervals(intervals):
	out = []
	for start, end in sorted(intervals):
		if end <= start:
			continue
		if out and start <= out[-1][1]:
			out[-1] = (out[-1][0], max(out[-1][1], end))
		else:
			out.append((start, end))
	return out


def uncovered_event(event, intervals):
	start, duration = event[:2]
	end = start + duration
	pos = start
	out = []
	i = max(0, bisect_right(intervals, (start, float("inf"))) - 1)
	while i < len(intervals) and intervals[i][0] < end:
		a, b = intervals[i]
		if b > pos:
			if a > pos:
				out.append((pos, min(a, end) - pos) + event[2:])
			pos = max(pos, b)
			if pos >= end:
				return out
		i += 1
	if pos < end:
		out.append((pos, end - pos) + event[2:])
	return out
