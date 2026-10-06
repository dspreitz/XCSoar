// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "NMEAWriter.hpp"
#include "Device/Port/Port.hpp"
#include "NMEA/Checksum.hpp"

#include <cassert>

#include <stdio.h>
#include <string.h>

#include <string>

void
PortWriteNMEA(Port &port, const char *line, OperationEnvironment &env)
{
  assert(line != nullptr);

  /* reasonable hard-coded timeout; do we need to make this a
     parameter? */
  static constexpr auto timeout = std::chrono::seconds(1);

  /* One write for the whole sentence.  Three of them -- '$', body,
     checksum -- can leave the port as three packets, and a Bluetooth
     SPP device with a short buffer (LXNAV S100, #3229) has been seen to
     do better when a command arrives in one piece. */
  char checksum[8];
  snprintf(checksum, sizeof(checksum), "*%02X\r\n", NMEAChecksum(line));

  std::string sentence;
  sentence.reserve(strlen(line) + 1 + strlen(checksum));
  sentence += '$';
  sentence += line;
  sentence += checksum;
  port.FullWrite(std::string_view{sentence}, env, timeout);
}
