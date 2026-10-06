// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

class Path;
class Port;
class RecordedFlightList;
struct RecordedFlightInfo;
class OperationEnvironment;

namespace Nano {
  bool ReadFlightList(Port &port, RecordedFlightList &flight_list,
                      OperationEnvironment &env);

  /**
   * @param file_window how many blocks of the LXNAV file transfer
   * the logger sends before it waits for an acknowledgement
   */
  bool DownloadFlight(Port &port, const RecordedFlightInfo &flight,
                      Path path, unsigned file_window,
                      OperationEnvironment &env);
}
