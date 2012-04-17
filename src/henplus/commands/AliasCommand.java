/*
 * This is free software, licensed under the Gnu Public License (GPL) get a copy from <http://www.gnu.org/licenses/gpl.html> $Id:
 * AliasCommand.java,v 1.17 2005-11-27 16:20:27 hzeller Exp $ author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import henplus.AbstractCommand;
import henplus.Command;
import henplus.CommandDispatcher;
import henplus.HenPlus;
import henplus.SQLSession;
import henplus.io.ConfigurationContainer;
import henplus.view.Column;
import henplus.view.ColumnMetaData;
import henplus.view.TableRenderer;
import henplus.view.util.SortedMatchIterator;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.StringTokenizer;
import java.util.TreeMap;

/**
 * A Command that handles Aliases.
 */
public final class AliasCommand extends AbstractCommand {

    private static final String ALIAS_FILENAME = "aliases";
    private static final ColumnMetaData[] DRV_META;
    static {
        DRV_META = new ColumnMetaData[2];
        DRV_META[0] = new ColumnMetaData("alias");
        DRV_META[1] = new ColumnMetaData("execute command");
    }

    private final ConfigurationContainer _config;
    private final SortedMap<String,String>/* <ClassName-String,Command-Class> */_aliases;
    private final CommandDispatcher _dispatcher;

    /**
     * to determine, if we got a recursion: one alias calls another alias which in turn calls the first one ..
     */
    private final Set<String> _currentExecutedAliases;

    /**
     * returns the command-strings this command can handle.
     */
    @Override
    public String[] getCommandList() {
        return new String[] { "list-aliases", "alias", "unalias" };
    }

    public AliasCommand(final HenPlus henplus) {
        _dispatcher = henplus.getDispatcher();
        _aliases = new TreeMap<String,String>();
        _currentExecutedAliases = new HashSet<String>();
        _config = henplus.createConfigurationContainer(ALIAS_FILENAME);
    }

    /**
     * initial load of aliases.
     */
    public void load() {
        final Map<String,String> props = _config.readProperties();
        for (Map.Entry<String,String> entry : props.entrySet()) {
            putAlias(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public boolean requiresValidSession(final String cmd) {
        return false;
    }

    private void putAlias(final String alias, final String value) {
        _aliases.put(alias, value);
        _dispatcher.registerAdditionalCommand(alias, this);
    }

    private void removeAlias(final String alias) {
        _aliases.remove(alias);
        _dispatcher.unregisterAdditionalCommand(alias);
    }

    /**
     * execute the command given.
     */
    @Override
    public int execute(final SQLSession currentSession, final String cmd, String param) {
        final StringTokenizer st = new StringTokenizer(param);
        final int argc = st.countTokens();

        if ("list-aliases".equals(cmd)) {
            if (argc != 0) {
                return SYNTAX_ERROR;
            }
            showAliases();
            return SUCCESS;
        } else if ("alias".equals(cmd)) {
            if (argc < 2) {
                return SYNTAX_ERROR;
            }
            final String alias = (String) st.nextElement();
            // no quoted aliases..
            if (alias.startsWith("\"") || alias.startsWith("'")) {
                return SYNTAX_ERROR;
            }
            // unless we override an alias, moan, if this command already
            // exists.
            if (!_aliases.containsKey(alias) && _dispatcher.containsCommand(alias)) {
                HenPlus.msg().println("cannot alias built-in command!");
                return EXEC_FAILED;
            }
            param = param.trim();
            for (int i = 0; i < param.length(); ++i) {
                if (Character.isWhitespace(param.charAt(i))) {
                    param = param.substring(i).trim();
                    break;
                }
            }
            final String value = stripQuotes(param); // rest of values.
            putAlias(alias, value);
        } else if ("unalias".equals(cmd)) {
            if (argc >= 1) {
                while (st.hasMoreElements()) {
                    final String alias = (String) st.nextElement();
                    if (!_aliases.containsKey(alias)) {
                        HenPlus.msg().println("unknown alias '" + alias + "'");
                    } else {
                        removeAlias(alias);
                    }
                }
                return SUCCESS;
            }
            return SYNTAX_ERROR;
        } else {
            final String toExecute = _aliases.get(cmd);
            // HenPlus.msg().println("key: '" + cmd + "' - exec: " + toExecute);
            if (toExecute == null) {
                return EXEC_FAILED;
            }
            // not session-proof:
            if (_currentExecutedAliases.contains(cmd)) {
                HenPlus.msg().println("Recursive call to aliases [" + cmd + "]. Stopping this senseless venture.");
                _currentExecutedAliases.clear();
                return EXEC_FAILED;
            }
            String commandValue = commandFor(toExecute, param);
            HenPlus.msg().println("execute alias: " + commandValue);
            _currentExecutedAliases.add(cmd);
            _dispatcher.execute(currentSession, commandValue);
            _currentExecutedAliases.clear();
        }
        return SUCCESS;
    }
    
    private String commandFor(final String toExecute, final String param) {
        //example:
        //toExecute = select * from $0 where $1 = $2 limit 1
        //param = 'mytab mycol 42'

        String commandValue = toExecute;
        //command contains $0, $1, ...
        if ( commandValue.matches(".*(\\$(\\d+)).*") ) {
            //replace: $0, $1, ... -> {0}, {1}, ...
            commandValue = commandValue.replaceAll("(\\$(\\d+))+", "{$2}");
        }
        //commandValue == select * from ${0} where ${1} = ${2}

        //command contains {0}, {1}, ....
        if ( commandValue.matches(".*(\\{\\d+(.??)*?\\}).*") ) {
            //for commandValue: parameterCount == 3
            int parameterCount = (" " + commandValue + " ").split("(\\{\\d+(.??)*?\\})").length - 1;
            String[] params = param.trim().split("[ ]");
            List<Object> realParams = new ArrayList<Object>();
            StringBuffer rest = new StringBuffer();
            for (int i = 0; i < params.length; i++) {
                Object parameter = params[ i ];
                //convert for ChoiceFormat
                if ( String.valueOf(parameter).trim().matches("\\d.*") ) {
                    parameter = Long.parseLong(String.valueOf(parameter).trim());
                }
                if ( i < parameterCount ) {
                    realParams.add(parameter);
                } else {
                    rest.append(" " + parameter);
                }
            }
            //now:
            //commandValue == select * from ${0} where ${1} = ${2}
            //rest == limit 1
            //realParams == { 'mytab', 'mycol', 42 }
            commandValue = MessageFormat.format(commandValue + rest, realParams.toArray());
        } else {
            commandValue = commandValue + param;
        }
        return commandValue;
    }

    private void showAliases() {
        DRV_META[0].resetWidth();
        DRV_META[1].resetWidth();
        final TableRenderer table = new TableRenderer(DRV_META, HenPlus.out());
        for(Map.Entry<String,String> entry : _aliases.entrySet()) {
            final Column[] row = new Column[2];
            row[0] = new Column(entry.getKey());
            row[1] = new Column(entry.getValue());
            table.addRow(row);
        }
        table.closeTable();
    }

    private String stripQuotes(String value) {
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        } else if (value.startsWith("\'") && value.endsWith("\'")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    @Override
    public Iterator<String> complete(final CommandDispatcher disp, final String partialCommand, final String lastWord) {
        final StringTokenizer st = new StringTokenizer(partialCommand);
        final String cmd = (String) st.nextElement();
        final int argc = st.countTokens();

        // list-aliases gets no names.
        if ("list-aliases".equals(cmd)) {
            return null;
        }

        /*
         * some completion within the alias/unalias commands.
         */
        if ("alias".equals(cmd) || "unalias".equals(cmd)) {
            final HashSet<String> alreadyGiven = new HashSet<String>();

            if ("alias".equals(cmd)) {
                // do not complete beyond first word.
                if (argc > ("".equals(lastWord) ? 0 : 1)) {
                    return null;
                }
            } else {
                /*
                 * remember all aliases, that have already been given on the
                 * commandline and exclude from completion.. cool, isn't it ?
                 */
                while (st.hasMoreElements()) {
                    alreadyGiven.add(st.nextToken());
                }
            }

            // ok, now return the list.
            return new SortedMatchIterator(lastWord, _aliases) {

                @Override
                protected boolean exclude(final String current) {
                    return alreadyGiven.contains(current);
                }
            };
        }

        /*
         * ok, someone tries to complete something that is a command. try to
         * find the actual command and ask that command to do the completion.
         */
        final String toExecute = _aliases.get(cmd);
        if (toExecute != null) {
            final Command c = disp.getCommandFrom(toExecute);
            if (c != null) {
                int i = 0;
                final String param = partialCommand;
                while (param.length() < i && Character.isWhitespace(param.charAt(i))) {
                    ++i;
                }
                while (param.length() < i && !Character.isWhitespace(param.charAt(i))) {
                    ++i;
                }
                return c.complete(disp, toExecute + param.substring(i), lastWord);
            }
        }

        return null;
    }

    @Override
    public void shutdown() {
        _config.storeProperties(_aliases, true, "Aliases...");
    }

    /**
     * return a descriptive string.
     */
    @Override
    public String getShortDescription() {
        return "handle Aliases";
    }

    @Override
    public String getSynopsis(final String cmd) {
        if ("list-aliases".equals(cmd)) {
            return cmd;
        } else if ("alias".equals(cmd)) {
            return cmd + " <alias-name> <command-to-execute>";
        } else if ("unalias".equals(cmd)) {
            return cmd + " <alias-name>";
        } else {
            /*
             * special aliased name..
             */
            return cmd;
        }
    }

    @Override
    public String getLongDescription(final String cmd) {
        String dsc = null;
        if ("list-aliases".equals(cmd)) {
            dsc = "\tList all aliases, that have been stored with the\n" + "\t'alias' command";
        } else if ("alias".equals(cmd)) {
            dsc = "\tAdd an alias for a command. This means, that you can\n"
                    + "\tgive a short name for a command you often use.  This\n" + "\tmight be as simple as\n"
                    + "\t   alias ls tables\n" + "\tto execute the tables command with a short 'ls'.\n"
                    + "\n\tFor longer commands it is even more helpful:\n" + "\t   alias size select count(*) from\n"
                    + "\tThis command  needs a table  name as a  parameter to\n"
                    + "\texpand  to  a  complete command.  So 'size students'\n"
                    + "\texpands to 'select count(*) from students' and yields\n" + "\tthe expected result.\n"
                    + "\n\tTo make life easier, HenPlus tries to determine the\n"
                    + "\tcommand  to be executed so that the  tab-completion\n"
                    + "\tworks even here; in this latter case it  would help\n" + "\tcomplete table names.";
        } else if ("unalias".equals(cmd)) {
            dsc = "\tremove an alias name";
        } else {
            // not session-proof:
            if (_currentExecutedAliases.contains(cmd)) {
                dsc = "\t[ this command cyclicly references itself ]";
            } else {
                _currentExecutedAliases.add(cmd);
                dsc = "\tThis is an alias for the command\n" + "\t   " + _aliases.get(cmd);

                String actualCmdStr = _aliases.get(cmd);
                if (actualCmdStr != null) {
                    final StringTokenizer st = new StringTokenizer(actualCmdStr);
                    actualCmdStr = st.nextToken();
                    final Command c = _dispatcher.getCommandFrom(actualCmdStr);
                    String longDesc = null;
                    if (c != null && (longDesc = c.getLongDescription(actualCmdStr)) != null) {
                        dsc += "\n\n\t..the following description could be determined for this";
                        dsc += "\n\t------- [" + actualCmdStr + "] ---\n";
                        dsc += longDesc;
                    }
                    _currentExecutedAliases.clear();
                }
            }
        }
        return dsc;
    }
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */
