/**
 * Minimal conductor action.
 */
function main(args) {
    // wrap toplevel params
    const action = args.action
    const state = args.state
    const params = args.params
    delete args.action
    delete args.state
    delete args.params
    return { action, state, params: Object.assign(args, params) }
}
